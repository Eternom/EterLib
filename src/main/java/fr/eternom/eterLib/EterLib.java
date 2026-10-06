package fr.eternom.eterLib;

import fr.eternom.eterLib.core.Cache;
import fr.eternom.eterLib.core.Lang;
import fr.eternom.eterLib.core.Sql;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.cache.RedisMessenger;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.listeners.Events;
import fr.eternom.eterLib.module.combat.CombatTracker;
import fr.eternom.eterLib.module.player.OnlineNames;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import fr.eternom.eterLib.module.server.ServerDirectory;
import fr.eternom.eterLib.module.teleport.TeleportCooldown;
import fr.eternom.eterLib.module.teleport.TeleportService;
import fr.eternom.eterLib.module.teleport.TeleportWarmup;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Socle commun des plugins Eter : une seule connexion SQL et Redis, une seule config réseau
 * (server-name, base, Redis, langue, couleurs), la table eter_players et les règles de téléportation.
 *
 * Un plugin Eter déclare `depend: [EterLib]` puis, dans son onEnable :
 * <pre>
 *     EterLib lib = EterLib.get();
 *     Database database = lib.database("eterhome_");          // ses tables : eterhome_*
 *     Messages messages = lib.messages(this, "en_us", "fr_fr"); // son dossier lang/
 *     RedisCache redis = lib.getRedis();                       // null si Redis est désactivé
 *     RedisMessenger messenger = lib.getMessenger();           // messages entre serveurs, null sans Redis
 * </pre>
 */
public final class EterLib extends JavaPlugin {

    /** Préfixe des tables d'EterLib lui-même (eter_players...). */
    private static final String TABLE_PREFIX = "eter_";
    private static final long HEARTBEAT_TICKS = 60 * 20;
    private static final long NAMES_REFRESH_TICKS = 10 * 20;

    private static EterLib instance;

    private String serverName;
    private String serverDisplayName;
    private Sql sql;
    private Cache cache;
    private RedisCache redis;
    private RedisMessenger messenger;
    private String defaultLocale;
    private Map<String, String> colors;
    private Messages messages;
    private PlayerDirectory players;
    private ServerDirectory servers;
    private OnlineNames onlineNames;
    private CombatTracker combat;
    private TeleportWarmup warmup;
    private TeleportService teleports;

    public static EterLib get() {
        if (instance == null) {
            throw new IllegalStateException("EterLib n'est pas chargé : ajouter depend: [EterLib] au plugin.yml");
        }
        return instance;
    }

    /**
     * À appeler en premier dans le onEnable d'un plugin Eter : vérifie qu'EterLib est au moins en version minimum,
     * sinon écrit pourquoi dans la console et désactive le plugin.
     * Un EterLib antérieur à 1.3.0 n'a pas cette méthode : l'appel lève alors une LinkageError, que le plugin intercepte.
     * @return false si le plugin a été désactivé
     */
    public static boolean requireVersion(JavaPlugin plugin, String minimum) {
        String installed = get().getPluginMeta().getVersion();
        if (isAtLeast(installed, minimum)) {
            return true;
        }
        plugin.getLogger().severe(plugin.getName() + " nécessite EterLib " + minimum + " ou plus récent (installé : "
                + installed + "). Plugin désactivé.");
        Bukkit.getPluginManager().disablePlugin(plugin);
        return false;
    }

    /** "1.10.0" >= "1.2.0" : compare les nombres un à un (un suffixe comme -SNAPSHOT est ignoré). */
    private static boolean isAtLeast(String version, String minimum) {
        String[] actual = version.split("[.-]");
        String[] wanted = minimum.split("[.-]");
        for (int i = 0; i < wanted.length; i++) {
            int a = i < actual.length && actual[i].matches("\\d+") ? Integer.parseInt(actual[i]) : 0;
            int w = Integer.parseInt(wanted[i]);
            if (a != w) {
                return a > w;
            }
        }
        return true;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverName = getConfig().getString("server-name", "").trim();
        if (serverName.isEmpty()) {
            throw new IllegalStateException("'server-name' est vide dans EterLib/config.yml : mets le nom de ce serveur dans le proxy");
        }
        serverDisplayName = getConfig().getString("server-display-name", "").trim();
        if (serverDisplayName.isEmpty()) {
            serverDisplayName = serverName;
        }
        defaultLocale = getConfig().getString("language.default", "en_us");
        colors = new HashMap<>();
        ConfigurationSection colorSection = getConfig().getConfigurationSection("language.colors");
        if (colorSection != null) {
            colorSection.getKeys(false).forEach(key -> colors.put(key, colorSection.getString(key)));
        }

        sql = new Sql(this);
        sql.connect();
        cache = new Cache(this);
        cache.connect();
        redis = cache.isEnabled() ? new RedisCache(cache) : null;
        messenger = cache.isEnabled() ? new RedisMessenger(cache, getLogger()) : null;
        messages = messages(this, "en_us", "fr_fr");

        Database database = database(TABLE_PREFIX);
        ConfigurationSection teleport = getConfig().getConfigurationSection("teleport");
        players = new PlayerDirectory(database, redis, serverName);
        players.clearServer();
        servers = new ServerDirectory(database);
        servers.register(serverName, serverDisplayName);
        servers.refresh();
        combat = new CombatTracker(seconds(teleport, "combat-tag", 10));
        warmup = new TeleportWarmup(this, messages, seconds(teleport, "warmup", 3),
                teleport == null || teleport.getBoolean("cancel-on-move", true));
        TeleportCooldown cooldown = new TeleportCooldown(database, redis, seconds(teleport, "cooldown", 30));
        teleports = new TeleportService(this, database, redis, messages, serverName, warmup, cooldown, combat);

        new Events(this, teleport == null || teleport.getBoolean("cancel-on-damage", true));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> players.heartbeat(
                Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList()), HEARTBEAT_TICKS, HEARTBEAT_TICKS);
        // Un serveur démarré plus tard (ou renommé) apparaît dans la minute
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, servers::refresh, HEARTBEAT_TICKS, HEARTBEAT_TICKS);
        onlineNames = new OnlineNames(players);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, onlineNames::refresh, 20, NAMES_REFRESH_TICKS);

        instance = this;
    }

    @Override
    public void onDisable() {
        instance = null;
        if (messenger != null) {
            messenger.close();
        }
        if (cache != null) {
            cache.close();
        }
        if (sql != null) {
            sql.close();
        }
    }

    /** Accès aux tables d'un plugin, préfixées par tablePrefix (ex : "eterhome_" -> eterhome_homes). */
    public Database database(String tablePrefix) {
        return new Database(sql, tablePrefix);
    }

    /** Messages d'un plugin : son dossier lang/, avec la langue par défaut et la palette communes. */
    public Messages messages(JavaPlugin plugin, String... bundledLocales) {
        Lang lang = new Lang(plugin, defaultLocale, colors, bundledLocales);
        lang.load();
        return new Messages(lang);
    }

    /** Nom de ce serveur dans le proxy (Velocity/BungeeCord). */
    public String getServerName() {
        return serverName;
    }

    /** Nom de ce serveur montré aux joueurs (server-display-name, sinon server-name), ex : "Survie". */
    public String getServerDisplayName() {
        return serverDisplayName;
    }

    /**
     * Nom montré aux joueurs pour n'importe quel serveur du réseau, à partir de son nom dans le proxy
     * ("survival" -> "Survie"). Lu en mémoire : utilisable sur le thread principal.
     */
    public String getServerDisplayName(String serverName) {
        return servers.displayName(serverName);
    }

    /** Messages entre serveurs (Redis pub/sub) ; null si Redis est désactivé. */
    public RedisMessenger getMessenger() {
        return messenger;
    }

    /** null si Redis est désactivé (cache.enabled: false). */
    public RedisCache getRedis() {
        return redis;
    }

    public PlayerDirectory getPlayers() {
        return players;
    }

    /** Pseudos des joueurs connectés (ce serveur et le réseau), pour la complétion avec Tab. */
    public OnlineNames getOnlineNames() {
        return onlineNames;
    }

    public TeleportService getTeleports() {
        return teleports;
    }

    public CombatTracker getCombat() {
        return combat;
    }

    public TeleportWarmup getWarmup() {
        return warmup;
    }

    private static Duration seconds(ConfigurationSection section, String key, int defaultSeconds) {
        return Duration.ofSeconds(section == null ? defaultSeconds : Math.max(0, section.getInt(key, defaultSeconds)));
    }
}
