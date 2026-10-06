package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Délai entre deux téléportations, partagé entre les serveurs : changer de serveur ne le remet pas à zéro.
 * Redis avec TTL si activé, sinon une table SQL. Les appels sont bloquants : hors du thread principal.
 */
public class TeleportCooldown {

    private static final String TABLE = "teleport_cooldowns";

    private final Database database;
    private final RedisCache redis; // null si Redis est désactivé
    private final Duration cooldown;

    public TeleportCooldown(Database database, RedisCache redis, Duration cooldown) {
        this.database = database;
        this.redis = redis;
        this.cooldown = cooldown;
        database.createTable(TABLE,
                Column.of("player", Column.Type.UUID).primaryKey(),
                Column.of("until", Column.Type.LONG).notNull());
        // Sans Redis, pas de TTL : les délais terminés restent en base, on les retire à chaque démarrage
        database.execute("DELETE FROM " + database.table(TABLE) + " WHERE until < ?", System.currentTimeMillis());
    }

    public boolean isEnabled() {
        return !cooldown.isZero();
    }

    /** Secondes restantes avant de pouvoir se téléporter, 0 si c'est possible. */
    public long remainingSeconds(UUID player) {
        if (!isEnabled()) {
            return 0;
        }
        long until = redis != null
                ? redis.get(key(player)).map(Long::parseLong).orElse(0L)
                : database.getFirst(TABLE, Map.of("player", player)).map(row -> row.getLong("until")).orElse(0L);
        long remaining = until - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public void start(UUID player) {
        if (!isEnabled()) {
            return;
        }
        long until = System.currentTimeMillis() + cooldown.toMillis();
        if (redis != null) {
            redis.set(key(player), String.valueOf(until), cooldown);
        } else {
            database.set(TABLE, Map.of("player", player, "until", until), "player");
        }
    }

    private String key(UUID player) {
        return "cooldown:" + player;
    }
}
