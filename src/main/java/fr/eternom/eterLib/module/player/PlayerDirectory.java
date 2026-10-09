package fr.eternom.eterLib.module.player;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterLib.module.vanish.Vanish;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tous les joueurs passés sur le réseau (table eter_players, base commune à tous les plugins et au futur site),
 * et le serveur où chacun est connecté. Les appels sont bloquants : à exécuter hors du thread principal.
 *
 * Présence : chaque serveur rafraîchit ses joueurs toutes les minutes ({@link #heartbeat()}). Un joueur dont la
 * présence n'a pas été rafraîchie depuis {@link #ONLINE_TIMEOUT} est considéré hors ligne (serveur planté).
 * La présence est aussi gardée dans Redis avec un TTL : lecture plus rapide.
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
    private final RedisCache redis;
    private final String serverName;
    /** Joueurs invisibles : absents des listes et des compteurs du réseau, « hors ligne » pour qui ne peut pas les voir. */
    private final Vanish hidden;

    public PlayerDirectory(Database database, RedisCache redis, String serverName, Vanish hidden) {
        this.database = database;
        this.redis = redis;
        this.serverName = serverName;
        this.hidden = hidden;
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
        redis.set(presenceKey(uuid), serverName, ONLINE_TIMEOUT);
    }

    /**
     * Ne marque hors ligne que si le joueur est encore noté sur CE serveur : en changeant de serveur,
     * l'arrivée sur le nouveau peut être enregistrée avant le départ de l'ancien.
     */
    public void quit(UUID uuid) {
        database.update(TABLE, offline(System.currentTimeMillis()), Map.of("uuid", uuid, "server", serverName));
        if (redis.get(presenceKey(uuid)).filter(serverName::equals).isPresent()) {
            redis.delete(presenceKey(uuid));
        }
    }

    /** Toutes les minutes : les joueurs de ce serveur sont toujours là. */
    public void heartbeat(Iterable<UUID> online) {
        long now = System.currentTimeMillis();
        database.update(TABLE, Map.of("last_seen", now), Map.of("server", serverName));
        online.forEach(uuid -> redis.set(presenceKey(uuid), serverName, ONLINE_TIMEOUT));
    }

    /** Serveur où le joueur est connecté, vide s'il est hors ligne. */
    public Optional<String> getServer(UUID uuid) {
        return redis.get(presenceKey(uuid));
    }

    /** Nombre de joueurs connectés sur tout le réseau (présence rafraîchie depuis moins de ONLINE_TIMEOUT), sans les invisibles. */
    public int countOnline() {
        return listOnline().size();
    }

    /** Joueurs connectés par serveur (sélecteur de serveurs d'un lobby), sans les invisibles. Bloquant (base). */
    public Map<String, Integer> countByServer() {
        return listOnline().stream().collect(Collectors.groupingBy(NetworkPlayer::server, HashMap::new, Collectors.summingInt(player -> 1)));
    }

    /** Joueurs connectés sur tout le réseau (ex : compléter un pseudo avec Tab), sans les invisibles. */
    public List<NetworkPlayer> listOnline() {
        long since = System.currentTimeMillis() - ONLINE_TIMEOUT.toMillis();
        return database.query("SELECT * FROM " + database.table(TABLE) + " WHERE server IS NOT NULL AND last_seen > ?", since)
                .stream().map(this::toPlayer).filter(player -> player.isOnline() && !hidden.isVanished(player.uuid())).toList();
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

    /**
     * Comme find, vu par asker : un invisible qu'il ne peut pas voir est montré hors ligne (serveur vide). C'est ce
     * qu'un plugin doit utiliser avant de montrer un joueur à un autre (/find, message privé, tpa...).
     */
    public Optional<NetworkPlayer> findFor(CommandSender asker, String name) {
        return find(name).map(player -> player.isOnline() && !hidden.canSee(asker, player.uuid())
                ? new NetworkPlayer(player.uuid(), player.name(), player.locale(), null, player.firstSeen(), player.lastSeen()) : player);
    }

    /** Le joueur connecté sous ce nom, s'il l'est et que asker peut le voir. */
    public Optional<NetworkPlayer> findOnlineFor(CommandSender asker, String name) {
        return findFor(asker, name).filter(NetworkPlayer::isOnline);
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
