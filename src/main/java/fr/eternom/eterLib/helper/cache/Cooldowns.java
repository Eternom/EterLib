package fr.eternom.eterLib.helper.cache;

import java.time.Duration;
import java.util.UUID;

/**
 * Un délai par joueur, valable sur tout le réseau : une clé Redis « name:uuid » qui expire seule (jamais en mémoire,
 * sinon il suffirait de changer de serveur). Un seul outil pour tous les délais (téléportation, /rtp, /report...).
 * Appels bloquants : hors du thread principal.
 * <pre>
 *     Cooldowns rtp = new Cooldowns(lib.getRedis(), "rtp:cooldown", Duration.ofMinutes(30));
 * </pre>
 */
public class Cooldowns {

    private final RedisCache redis;
    private final String name;
    private final Duration duration;

    public Cooldowns(RedisCache redis, String name, Duration duration) {
        this.redis = redis;
        this.name = name;
        this.duration = duration;
    }

    /** Un délai de 0 : jamais d'attente. */
    public boolean isEnabled() {
        return !duration.isZero() && !duration.isNegative();
    }

    /** Secondes restantes avant de pouvoir recommencer, 0 si c'est possible. */
    public long remainingSeconds(UUID player) {
        if (!isEnabled()) {
            return 0;
        }
        long until = redis.get(key(player)).map(Long::parseLong).orElse(0L);
        long remaining = until - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public void start(UUID player) {
        if (isEnabled()) {
            redis.set(key(player), String.valueOf(System.currentTimeMillis() + duration.toMillis()), duration);
        }
    }

    /**
     * Démarre le délai seulement s'il n'est pas en cours, en une seule opération (deux serveurs en même temps : un seul
     * gagne). false si le délai courait déjà.
     */
    public boolean tryStart(UUID player) {
        return !isEnabled() || redis.setIfAbsent(key(player), String.valueOf(System.currentTimeMillis() + duration.toMillis()), duration);
    }

    public void clear(UUID player) {
        redis.delete(key(player));
    }

    private String key(UUID player) {
        return name + ":" + player;
    }
}
