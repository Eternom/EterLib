package fr.eternom.eterLib.module.combat;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joueurs en combat (contre un joueur ou un monstre) : ils ne peuvent pas se téléporter
 * pendant tagDuration après le dernier coup donné ou reçu. Un combat contre un JOUEUR retient aussi l'adversaire, pour
 * la même durée (ex : EterEssential tue sur place celui qui se déconnecte en plein combat).
 * Lu en mémoire (thread principal), et recopié dans Redis (combat:<uuid>, au plus toutes les 2 s par joueur) : le
 * serveur où le joueur arrive le relit (load), changer de serveur n'efface pas le combat.
 */
public class CombatTracker {

    private static final String KEY = "combat:";
    private static final long WRITE_EVERY_MILLIS = 2_000;

    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Opponent> opponents = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWritten = new ConcurrentHashMap<>();
    private final long tagMillis;
    private final JavaPlugin plugin;
    private final RedisCache redis;

    public CombatTracker(Duration tagDuration) {
        this(tagDuration, null, null);
    }

    public CombatTracker(Duration tagDuration, JavaPlugin plugin, RedisCache redis) {
        this.tagMillis = tagDuration.toMillis();
        this.plugin = plugin;
        this.redis = redis;
    }

    /** Le dernier joueur affronté et la fin de ce combat. */
    private record Opponent(String name, long until) {
    }

    public void tag(UUID player) {
        if (tagMillis > 0) {
            long until = System.currentTimeMillis() + tagMillis;
            taggedUntil.put(player, until);
            share(player, until);
        }
    }

    /** Un coup entre deux joueurs : chacun en combat, contre l'autre. */
    public void tagPlayers(UUID victim, String victimName, UUID attacker, String attackerName) {
        if (tagMillis <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + tagMillis;
        taggedUntil.put(victim, until);
        taggedUntil.put(attacker, until);
        opponents.put(victim, new Opponent(attackerName, until));
        opponents.put(attacker, new Opponent(victimName, until));
        share(victim, until);
        share(attacker, until);
    }

    /** Recopié dans Redis, sans écrire à chaque coup (une ferme à monstres en donne des dizaines par seconde). */
    private void share(UUID player, long until) {
        if (redis == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastWritten.get(player);
        if (last != null && now - last < WRITE_EVERY_MILLIS) {
            return;
        }
        lastWritten.put(player, now);
        Tasks.async(plugin, () -> redis.set(KEY + player, String.valueOf(until), Duration.ofMillis(Math.max(1, until - now))),
                "Combat non partagé");
    }

    /** Bloquant (Redis) : à l'arrivée d'un joueur, son combat venu d'un autre serveur. */
    public void load(UUID player) {
        if (redis == null) {
            return;
        }
        redis.get(KEY + player).ifPresent(value -> {
            try {
                long until = Long.parseLong(value);
                taggedUntil.merge(player, until, Math::max);
            } catch (NumberFormatException ignored) {
                // valeur abîmée : ignorée
            }
        });
    }

    /**
     * Bloquant (Redis) : secondes de combat d'un joueur où qu'il soit (sur un autre serveur, à 2 s près). Ici : la
     * mémoire.
     */
    public long networkSeconds(UUID player) {
        long local = remainingSeconds(player);
        if (local > 0 || redis == null) {
            return local;
        }
        return redis.get(KEY + player).map(value -> {
            try {
                long remaining = Long.parseLong(value) - System.currentTimeMillis();
                return remaining <= 0 ? 0L : (remaining + 999) / 1000;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    /** Le joueur affronté si ce combat contre un joueur dure encore ; vide sinon (fini, ou seulement des monstres). */
    public Optional<String> opponent(UUID player) {
        Opponent opponent = opponents.get(player);
        if (opponent == null) {
            return Optional.empty();
        }
        if (opponent.until() < System.currentTimeMillis()) {
            opponents.remove(player);
            return Optional.empty();
        }
        return Optional.of(opponent.name());
    }

    /** Secondes de combat restantes, 0 si le joueur n'est pas en combat. */
    public long remainingSeconds(UUID player) {
        Long until = taggedUntil.get(player);
        if (until == null) {
            return 0;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            taggedUntil.remove(player);
            lastWritten.remove(player);
            return 0;
        }
        return (remaining + 999) / 1000;
    }
}
