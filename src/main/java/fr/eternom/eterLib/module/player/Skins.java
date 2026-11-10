package fr.eternom.eterLib.module.player;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import fr.eternom.eterLib.helper.cache.RedisCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les skins des joueurs pour les têtes des menus (Items.head) : une tête faite d'un simple uuid + pseudo s'affiche en
 * Steve tant que le client n'a pas la texture. La texture de chaque joueur est retenue à sa connexion (n'importe quel
 * serveur : Redis « skin:<uuid> », 30 jours) et ajoutée aux têtes des menus :
 * - joueur connecté ici : son profil ;
 * - déjà vu : la mémoire de ce serveur ;
 * - sinon : lue dans Redis en tâche de fond (la tête suivante l'aura) ; un menu construit en tâche de fond peut
 *   appeler prefetch avant, pour l'avoir tout de suite.
 */
public final class Skins {

    private static final String KEY = "skin:";
    private static final Duration TTL = Duration.ofDays(30);
    private static final Map<UUID, ProfileProperty> CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
    private static volatile JavaPlugin plugin;
    private static volatile RedisCache redis;

    private Skins() {
    }

    public static void init(JavaPlugin owner, RedisCache cache) {
        plugin = owner;
        redis = cache;
    }

    /** Connexion : sa texture est retenue (mémoire, et Redis pour les autres serveurs). Thread principal. */
    public static void remember(Player player) {
        textures(player.getPlayerProfile()).ifPresent(textures -> {
            UUID uuid = player.getUniqueId();
            CACHE.put(uuid, textures);
            if (plugin != null && redis != null) {
                String value = textures.getValue() + "\n" + (textures.getSignature() == null ? "" : textures.getSignature());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        redis.set(KEY + uuid, value, TTL);
                    } catch (RuntimeException ignored) {
                        // Redis indisponible : la mémoire de ce serveur suffit pour l'instant
                    }
                });
            }
        });
    }

    /** Le profil avec sa texture si on la connaît (sinon tel quel, et la texture est cherchée pour la prochaine fois). */
    public static PlayerProfile enrich(PlayerProfile profile) {
        if (profile == null || textures(profile).isPresent() || profile.getId() == null) {
            return profile;
        }
        UUID uuid = profile.getId();
        Player online = Bukkit.getPlayer(uuid);
        Optional<ProfileProperty> known = online != null ? textures(online.getPlayerProfile()) : Optional.ofNullable(CACHE.get(uuid));
        if (known.isPresent()) {
            profile.setProperty(known.get());
            return profile;
        }
        fetchLater(uuid);
        return profile;
    }

    /** Charge les textures de ces joueurs depuis Redis. Bloquant : depuis une tâche de fond, avant de construire un menu. */
    public static void prefetch(Collection<UUID> players) {
        for (UUID uuid : players) {
            if (!CACHE.containsKey(uuid)) {
                load(uuid);
            }
        }
    }

    private static void fetchLater(UUID uuid) {
        if (plugin == null || redis == null || !PENDING.add(uuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                load(uuid);
            } finally {
                PENDING.remove(uuid);
            }
        });
    }

    private static void load(UUID uuid) {
        if (redis == null) {
            return;
        }
        try {
            redis.get(KEY + uuid).ifPresent(value -> {
                String[] parts = value.split("\n", 2);
                String signature = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
                CACHE.put(uuid, new ProfileProperty("textures", parts[0], signature));
            });
        } catch (RuntimeException ignored) {
            // Redis indisponible : la tête reste celle par défaut
        }
    }

    private static Optional<ProfileProperty> textures(PlayerProfile profile) {
        if (profile == null) {
            return Optional.empty();
        }
        return profile.getProperties().stream().filter(property -> "textures".equals(property.getName())).findFirst();
    }
}
