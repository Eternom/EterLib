package fr.eternom.eterLib;

import fr.eternom.eterLib.core.Cache;
import fr.eternom.eterLib.core.Lang;
import fr.eternom.eterLib.core.Sql;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.cache.RedisMessenger;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Durations;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sidebar.SidebarOverrides;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.listeners.Events;
import fr.eternom.eterLib.module.combat.CombatTracker;
import fr.eternom.eterLib.module.player.OnlineNames;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import fr.eternom.eterLib.module.server.ServerDirectory;
import fr.eternom.eterLib.module.tag.PlayerTags;
import fr.eternom.eterLib.module.teleport.TeleportCooldown;
import fr.eternom.eterLib.module.teleport.TeleportService;
import fr.eternom.eterLib.module.teleport.TeleportWarmup;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
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
 *     RedisCache redis = lib.getRedis();                       // Redis (obligatoire)
 *     RedisMessenger messenger = lib.getMessenger();           // messages entre serveurs
 * </pre>
 */
public final class EterLib extends JavaPlugin {

    /** Préfixe des tables d'EterLib lui-même (eter_players...). */
    private static final String TABLE_PREFIX = "eter_";
    private static final long HEARTBEAT_TICKS = 60 * 20;
    private static final long SERVER_HEARTBEAT_TICKS = 20 * 20;
    private static final long NAMES_REFRESH_TICKS = 10 * 20;
    /** Préfixe de tous les messages des plugins Eter si language.prefix est absent (ancienne config). */
    private static final String DEFAULT_PREFIX = "<gradient:#FF7A00:#FFB347><bold>Core</bold></gradient> <dark_gray>» ";

    private static EterLib instance;

    private String serverName;
    private String serverDisplayName;
    private Sql sql;
    private Cache cache;
    private RedisCache redis;
    private RedisMessenger messenger;
    private String defaultLocale;
    private Map<String, String> colors;
    private String prefix;
    private Messages messages;
    /** Langues d'EterLib, textes communs repris par tous les plugins. */
    private Lang ownLang;
    private PlayerDirectory players;
    private ServerDirectory servers;
    private OnlineNames onlineNames;
    private CombatTracker combat;
    private TeleportWarmup warmup;
    private TeleportService teleports;
    private final SidebarOverrides sidebars = new SidebarOverrides();
    private PlayerTags playerTags;

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
        prefix = getConfig().getString("language.prefix", DEFAULT_PREFIX);
        colors = new HashMap<>();
        ConfigurationSection colorSection = getConfig().getConfigurationSection("language.colors");
        if (colorSection != null) {
            colorSection.getKeys(false).forEach(key -> colors.put(key, colorSection.getString(key)));
        }

        sql = new Sql(this);
        sql.connect();
        cache = new Cache(this);
        cache.connect();
        redis = new RedisCache(cache);
        messenger = new RedisMessenger(cache, getLogger());
        messages = messages(this, "en_us", "fr_fr");

        Database database = database(TABLE_PREFIX);
        // Tables des replis sans Redis (avant 1.8.0, Redis obligatoire depuis) : pas de table morte dans la base
        database.execute("DROP TABLE IF EXISTS " + database.table("pending_teleports") + ", " + database.table("teleport_cooldowns"));
        ConfigurationSection teleport = getConfig().getConfigurationSection("teleport");
        players = new PlayerDirectory(database, redis, serverName);
        players.clearServer();
        servers = new ServerDirectory(database, serverName);
        servers.register(serverDisplayName);
        combat = new CombatTracker(seconds(teleport, "combat-tag", 10));
        warmup = new TeleportWarmup(this, messages, seconds(teleport, "warmup", 3),
                teleport == null || teleport.getBoolean("cancel-on-move", true));
        TeleportCooldown cooldown = new TeleportCooldown(redis, seconds(teleport, "cooldown", 30));
        teleports = new TeleportService(this, redis, messages, serverName, servers, warmup, cooldown, combat);
        playerTags = new PlayerTags(this);

        new Events(this, teleport == null || teleport.getBoolean("cancel-on-damage", true));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> players.heartbeat(
                Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList()), HEARTBEAT_TICKS, HEARTBEAT_TICKS);
        // Signe de vie de ce serveur, et état des autres (en ligne, nom affiché), toutes les 20 s
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, servers::heartbeat, SERVER_HEARTBEAT_TICKS, SERVER_HEARTBEAT_TICKS);
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

    /**
     * Messages d'un plugin entre les serveurs (Redis, canal channel) : types, gestionnaires sur le thread principal,
     * et notify pour prévenir un joueur où qu'il soit.
     */
    public NetworkBus network(JavaPlugin plugin, String channel, Messages pluginMessages) {
        return new NetworkBus(plugin, messenger, channel, pluginMessages, serverName);
    }

    /** Durée lisible dans la langue du joueur ("2 j 3 h", "30 min 5 s"...), avec les textes d'EterLib. */
    public String formatDuration(CommandSender receiver, long seconds) {
        return Durations.format(messages, receiver, seconds);
    }

    /**
     * Bouton « Retour » / « Fermer » d'un menu : command vide = fermer le menu, sinon la commande lancée pour le
     * joueur (ex : "profile"), pour relier les menus. À lire dans la config du plugin (menus.<menu>.back-command).
     */
    public BackButton backButton(String command) {
        return new BackButton(command, messages);
    }

    /**
     * Messages d'un plugin : son dossier lang/, avec la langue par défaut et la palette communes. Une clé absente du
     * plugin reprend le texte commun d'EterLib (command.players-only, error.generic, economy.unavailable, dialog.cancel,
     * player.unknown...) : inutile de les répéter dans chaque plugin.
     */
    public Messages messages(JavaPlugin plugin, String... bundledLocales) {
        Lang lang = new Lang(plugin, defaultLocale, colors, prefix, plugin == this ? null : ownLang, bundledLocales);
        lang.load();
        if (plugin == this) {
            ownLang = lang;
        }
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

    /** Messages entre serveurs (Redis pub/sub). */
    public RedisMessenger getMessenger() {
        return messenger;
    }

    /** Redis, obligatoire : jamais null une fois EterLib démarré. */
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

    /** Sidebar temporaire d'un joueur (ex : quête suivie), dessinée par EterTab à la place de la sienne. */
    public SidebarOverrides getSidebars() {
        return sidebars;
    }

    /** Étiquettes d'un joueur (<tag_nom>) pour la sidebar d'EterTab et la liste Tab du réseau, ex : son métier. */
    public PlayerTags getPlayerTags() {
        return playerTags;
    }

    /** Serveurs du réseau : nom affiché, en ligne ou non (relu toutes les 20 s). */
    public ServerDirectory getServers() {
        return servers;
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
