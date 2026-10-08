package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.helper.cache.RedisCache;

import java.time.Duration;
import java.util.UUID;

/**
 * Délai entre deux téléportations, partagé entre les serveurs : changer de serveur ne le remet pas à zéro.
 * Gardé dans Redis avec un TTL. Les appels sont bloquants : hors du thread principal.
 */
public class TeleportCooldown {

    private final RedisCache redis;
    private final Duration cooldown;

    public TeleportCooldown(RedisCache redis, Duration cooldown) {
        this.redis = redis;
        this.cooldown = cooldown;
    }

    public boolean isEnabled() {
        return !cooldown.isZero();
    }

    /** Secondes restantes avant de pouvoir se téléporter, 0 si c'est possible. */
    public long remainingSeconds(UUID player) {
        if (!isEnabled()) {
            return 0;
        }
        long until = redis.get(key(player)).map(Long::parseLong).orElse(0L);
        long remaining = until - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public void start(UUID player) {
        if (!isEnabled()) {
            return;
        }
        long until = System.currentTimeMillis() + cooldown.toMillis();
        redis.set(key(player), String.valueOf(until), cooldown);
    }

    private String key(UUID player) {
        return "cooldown:" + player;
    }
}
