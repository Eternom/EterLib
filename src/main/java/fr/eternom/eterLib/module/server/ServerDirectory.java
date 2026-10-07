package fr.eternom.eterLib.module.server;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les serveurs du réseau (table eter_servers) : nom affiché de chacun (server-display-name d'EterLib) et dernier
 * signe de vie. Chaque serveur y écrit le sien au démarrage puis toutes les 20 s ({@link #heartbeat}) : tous les
 * plugins Paper lisent les mêmes noms (un home sur « survival » s'affiche « Survie » partout) et savent quels
 * serveurs tournent (sélecteur de serveurs d'un lobby). Gardés en mémoire, relus à chaque battement.
 */
public class ServerDirectory {

    /** Sans signe de vie depuis plus longtemps, un serveur est considéré hors ligne (3 battements manqués). */
    public static final Duration ONLINE_TIMEOUT = Duration.ofSeconds(60);

    private static final String TABLE = "servers";

    private record Server(String displayName, long lastSeen) {
    }

    private final Database database;
    private final String serverName;
    private final Map<String, Server> servers = new ConcurrentHashMap<>();

    public ServerDirectory(Database database, String serverName) {
        this.database = database;
        this.serverName = serverName;
        database.createTable(TABLE,
                Column.of("name", Column.Type.STRING).length(64).primaryKey(),
                Column.of("display_name", Column.Type.STRING).length(64).notNull(),
                Column.of("last_seen", Column.Type.LONG).notNull());
    }

    /** Bloquant (base) : au démarrage, enregistre ce serveur puis lit les autres. */
    public void register(String displayName) {
        database.set(TABLE, Map.of("name", serverName, "display_name", displayName, "last_seen", System.currentTimeMillis()), "name");
        heartbeat();
    }

    /** Bloquant (base) : ce serveur tourne toujours, puis relit tous les serveurs. */
    public void heartbeat() {
        database.update(TABLE, Map.of("last_seen", System.currentTimeMillis()), Map.of("name", serverName));
        for (Row row : database.get(TABLE, Map.of())) {
            servers.put(row.getString("name"), new Server(row.getString("display_name"), row.getLong("last_seen")));
        }
    }

    /** Nom affiché d'un serveur (son nom dans le proxy s'il n'est pas connu). Ne touche pas à la base. */
    public String displayName(String server) {
        if (server == null) {
            return null;
        }
        Server known = servers.get(server);
        return known == null ? server : known.displayName();
    }

    /** Le serveur a donné signe de vie récemment (état relu toutes les 20 s, sans toucher à la base). */
    public boolean isOnline(String server) {
        Server known = servers.get(server);
        return known != null && System.currentTimeMillis() - known.lastSeen() < ONLINE_TIMEOUT.toMillis();
    }
}
