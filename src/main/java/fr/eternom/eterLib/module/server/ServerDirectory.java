package fr.eternom.eterLib.module.server;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Noms affichés de tous les serveurs du réseau (table eter_servers) : chaque serveur y écrit le sien au démarrage
 * (server-display-name d'EterLib), et tous les plugins Paper lisent les mêmes noms, ex : un home sur « survival »
 * s'affiche « Survie » partout. Gardés en mémoire, relus régulièrement ({@link #refresh}).
 */
public class ServerDirectory {

    private static final String TABLE = "servers";

    private final Database database;
    private final Map<String, String> displayNames = new ConcurrentHashMap<>();

    public ServerDirectory(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("name", Column.Type.STRING).length(64).primaryKey(),
                Column.of("display_name", Column.Type.STRING).length(64).notNull(),
                Column.of("last_seen", Column.Type.LONG).notNull());
    }

    /** Bloquant (base) : au démarrage, enregistre ce serveur. */
    public void register(String serverName, String displayName) {
        database.set(TABLE, Map.of("name", serverName, "display_name", displayName,
                "last_seen", System.currentTimeMillis()), "name");
        displayNames.put(serverName, displayName);
    }

    /** Bloquant (base) : relit les noms de tous les serveurs. */
    public void refresh() {
        for (Row row : database.get(TABLE, Map.of())) {
            displayNames.put(row.getString("name"), row.getString("display_name"));
        }
    }

    /** Nom affiché d'un serveur (son nom dans le proxy s'il n'est pas connu). Ne touche pas à la base. */
    public String displayName(String serverName) {
        return serverName == null ? null : displayNames.getOrDefault(serverName, serverName);
    }
}
