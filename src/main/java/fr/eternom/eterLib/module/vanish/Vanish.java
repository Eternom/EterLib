package fr.eternom.eterLib.module.vanish;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.cache.RedisMessenger;
import fr.eternom.eterLib.module.tag.PlayerTags;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joueurs invisibles (staff), sur tout le réseau : cachés aux joueurs sans eter.vanish.see sur chaque serveur, absents
 * des listes et des compteurs du réseau (PlayerDirectory, complétion des pseudos), de la liste Tab du proxy (étiquette
 * « vanished » pour EterTab) ; les plugins qui montrent un joueur à un autre (messages privés, /list, /find) demandent
 * canSee. L'état est gardé dans Redis (hash eter:vanished) jusqu'à ce qu'il soit retiré : il suit le joueur d'un serveur
 * à l'autre et d'une connexion à l'autre. Chaque serveur en garde une copie, tenue à jour par le canal eter:vanish.
 */
public class Vanish implements Listener {

    public static final String SEE_PERMISSION = "eter.vanish.see";
    /** Étiquette lue par EterTab-Velocity pour retirer le joueur de la liste Tab. */
    public static final String TAG = "vanished";
    private static final String KEY = "eter:vanished";
    private static final String CHANNEL = "eter:vanish";

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final RedisMessenger messenger;
    private final PlayerTags tags;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public Vanish(JavaPlugin plugin, RedisCache redis, RedisMessenger messenger, PlayerTags tags) {
        this.plugin = plugin;
        this.redis = redis;
        this.messenger = messenger;
        this.tags = tags;
        redis.getHash(KEY).keySet().forEach(uuid -> vanished.add(UUID.fromString(uuid)));
        // « +uuid » ou « -uuid » : copie tenue à jour (le serveur du joueur a déjà tout appliqué)
        messenger.subscribe(CHANNEL, message -> {
            UUID uuid = UUID.fromString(message.substring(1));
            if (message.charAt(0) == '+') {
                vanished.add(uuid);
            } else {
                vanished.remove(uuid);
            }
        });
    }

    public boolean isVanished(UUID player) {
        return vanished.contains(player);
    }

    /** viewer peut-il voir target (en ligne, dans les listes, lui écrire) ? Lui-même et le staff : toujours. */
    public boolean canSee(CommandSender viewer, UUID target) {
        return !vanished.contains(target) || viewer.hasPermission(SEE_PERMISSION)
                || (viewer instanceof Player player && player.getUniqueId().equals(target));
    }

    /** Thread principal : rend le joueur invisible (ou visible) partout, tout de suite et jusqu'à ce qu'on change. */
    public void set(Player player, boolean hidden) {
        UUID uuid = player.getUniqueId();
        if (hidden ? !vanished.add(uuid) : !vanished.remove(uuid)) {
            return; // déjà dans cet état
        }
        apply(player);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (hidden) {
                    redis.setHashField(KEY, uuid.toString(), "1");
                } else {
                    redis.deleteHashField(KEY, uuid.toString());
                }
                messenger.publish(CHANNEL, (hidden ? "+" : "-") + uuid);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Invisibilité de " + player.getName() + " non transmise au réseau : " + e.getMessage());
            }
        });
    }

    /** Tôt : un invisible qui arrive est caché aux autres, et les invisibles déjà là sont cachés au nouveau venu. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        if (vanished.contains(joining.getUniqueId())) {
            apply(joining);
        }
        if (!joining.hasPermission(SEE_PERMISSION)) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other != joining && vanished.contains(other.getUniqueId())) {
                    joining.hidePlayer(plugin, other);
                }
            }
        }
    }

    private void apply(Player player) {
        boolean hidden = vanished.contains(player.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player) {
                continue;
            }
            if (hidden && !other.hasPermission(SEE_PERMISSION)) {
                other.hidePlayer(plugin, player);
            } else {
                other.showPlayer(plugin, player);
            }
        }
        tags.set(player, TAG, hidden ? "1" : "");
    }
}
