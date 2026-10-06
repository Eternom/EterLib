package fr.eternom.eterLib.module.player;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tous les joueurs passés sur le réseau (table eter_players, base commune à tous les plugins et au futur site),
 * et le serveur où chacun est connecté. Les appels sont bloquants : à exécuter hors du thread principal.
 *
 * Présence : chaque serveur rafraîchit ses joueurs toutes les minutes ({@link #heartbeat()}). Un joueur dont la
 * présence n'a pas été rafraîchie depuis {@link #ONLINE_TIMEOUT} est considéré hors ligne (serveur planté).
 * Avec Redis, la présence y est aussi gardée avec un TTL : lecture plus rapide.
 */
public class PlayerDirectory {

    public static final Duration ONLINE_TIMEOUT = Duration.ofMinutes(2);
    private static final String TABLE = "players";

    /** server = null si le joueur est hors ligne. */
    public record NetworkPlayer(UUID uuid, String name, String locale, String server, long firstSeen, long lastSeen) {

        public boolean isOnline() {
            return server != null;
        }
    }

    private final Database database;
    private final RedisCache redis; // null si Redis est désactivé
    private final String serverName;

    public PlayerDirectory(Database database, RedisCache redis, String serverName) {
        this.database = database;
        this.redis = redis;
        this.serverName = serverName;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("name", Column.Type.STRING).length(16).notNull(),
                Column.of("locale", Column.Type.STRING).length(16),
                Column.of("server", Column.Type.STRING).length(64),
                Column.of("first_seen", Column.Type.LONG).notNull(),
                Column.of("last_seen", Column.Type.LONG).notNull());
    }

    /** Au démarrage : personne n'est encore connecté ici, on efface les présences laissées par un arrêt brutal. */
    public void clearServer() {
        database.update(TABLE, offline(System.currentTimeMillis()), Map.of("server", serverName));
    }

    public void join(UUID uuid, String name, String locale) {
        long now = System.currentTimeMillis();
        boolean known = database.getFirst(TABLE, Map.of("uuid", uuid)).isPresent();
        Map<String, Object> values = Map.of("name", name, "locale", locale, "server", serverName, "last_seen", now);
        if (known) {
            database.update(TABLE, values, Map.of("uuid", uuid));
        } else {
            database.insert(TABLE, Map.of("uuid", uuid, "name", name, "locale", locale, "server", serverName,
                    "first_seen", now, "last_seen", now));
        }
        if (redis != null) {
            redis.set(presenceKey(uuid), serverName, ONLINE_TIMEOUT);
        }
    }

    /**
     * Ne marque hors ligne que si le joueur est encore noté sur CE serveur : en changeant de serveur,
     * l'arrivée sur le nouveau peut être enregistrée avant le départ de l'ancien.
     */
    public void quit(UUID uuid) {
        database.update(TABLE, offline(System.currentTimeMillis()), Map.of("uuid", uuid, "server", serverName));
        if (redis != null && redis.get(presenceKey(uuid)).filter(serverName::equals).isPresent()) {
            redis.delete(presenceKey(uuid));
        }
    }

    /** Toutes les minutes : les joueurs de ce serveur sont toujours là. */
    public void heartbeat(Iterable<UUID> online) {
        long now = System.currentTimeMillis();
        database.update(TABLE, Map.of("last_seen", now), Map.of("server", serverName));
        if (redis != null) {
            online.forEach(uuid -> redis.set(presenceKey(uuid), serverName, ONLINE_TIMEOUT));
        }
    }

    /** Serveur où le joueur est connecté, vide s'il est hors ligne. */
    public Optional<String> getServer(UUID uuid) {
        if (redis != null) {
            return redis.get(presenceKey(uuid));
        }
        return database.getFirst(TABLE, Map.of("uuid", uuid)).map(this::toPlayer).map(NetworkPlayer::server);
    }

    /** Nombre de joueurs connectés sur tout le réseau (présence rafraîchie depuis moins de ONLINE_TIMEOUT). */
    public int countOnline() {
        long since = System.currentTimeMillis() - ONLINE_TIMEOUT.toMillis();
        return database.query("SELECT COUNT(*) AS online FROM " + database.table(TABLE)
                        + " WHERE server IS NOT NULL AND last_seen > ?", since)
                .stream().findFirst().map(row -> row.getInt("online")).orElse(0);
    }

    /** Joueurs connectés sur tout le réseau (ex : compléter un pseudo avec Tab). */
    public List<NetworkPlayer> listOnline() {
        long since = System.currentTimeMillis() - ONLINE_TIMEOUT.toMillis();
        return database.query("SELECT * FROM " + database.table(TABLE) + " WHERE server IS NOT NULL AND last_seen > ?", since)
                .stream().map(this::toPlayer).toList();
    }

    public Optional<NetworkPlayer> get(UUID uuid) {
        return database.getFirst(TABLE, Map.of("uuid", uuid)).map(this::toPlayer);
    }

    /** Le dernier joueur vu sous ce nom (un pseudo peut avoir changé de propriétaire). Insensible à la casse. */
    public Optional<NetworkPlayer> find(String name) {
        return database.get(TABLE, Map.of("name", name)).stream()
                .max(Comparator.comparingLong(row -> row.getLong("last_seen")))
                .map(this::toPlayer);
    }

    private NetworkPlayer toPlayer(Row row) {
        String server = row.getString("server");
        long lastSeen = row.getLong("last_seen");
        boolean online = server != null
                && System.currentTimeMillis() - lastSeen < ONLINE_TIMEOUT.toMillis();
        return new NetworkPlayer(row.getUUID("uuid"), row.getString("name"), row.getString("locale"),
                online ? server : null, row.getLong("first_seen"), lastSeen);
    }

    /** server = NULL : hors ligne (HashMap, car Map.of refuse null). */
    private static Map<String, Object> offline(long lastSeen) {
        Map<String, Object> values = new HashMap<>();
        values.put("server", null);
        values.put("last_seen", lastSeen);
        return values;
    }

    private String presenceKey(UUID uuid) {
        return "presence:" + uuid;
    }
}
