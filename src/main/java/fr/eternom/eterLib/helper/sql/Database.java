package fr.eternom.eterLib.helper.sql;

import fr.eternom.eterLib.core.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Requêtes MySQL / MariaDB sans SQL brut, sur une connexion {@link Sql}.
 * Une instance par plugin, avec son préfixe de tables (ex : "eterhome_") : les noms de tables passés aux méthodes
 * sont préfixés automatiquement (voir {@link #table(String)}), deux plugins ne peuvent donc pas se marcher dessus.
 * Les appels sont bloquants : à exécuter hors du thread principal.
 * Les noms de tables et de colonnes sont en minuscules (a-z, 0-9, _).
 */
public class Database {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern TABLE_PREFIX = Pattern.compile("[a-z][a-z0-9_]*_");

    private final Sql sql;
    private final String tablePrefix;

    /** @param tablePrefix préfixe des tables du plugin : minuscules, chiffres et _, terminé par _ (ex : "eterhome_") */
    public Database(Sql sql, String tablePrefix) {
        if (!TABLE_PREFIX.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("Préfixe de tables invalide : \"" + tablePrefix + "\" (ex : eterhome_)");
        }
        this.sql = sql;
        this.tablePrefix = tablePrefix;
    }

    public void createTable(String table, Column... columns) {
        List<String> definitions = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();

        for (Column column : columns) {
            if (column.isAutoIncrement()) {
                definitions.add(id(column.getName()) + " " + columnType(column) + " AUTO_INCREMENT PRIMARY KEY");
                continue;
            }
            definitions.add(columnDefinition(column));
            if (column.isPrimaryKey()) {
                primaryKeys.add(id(column.getName()));
            }
        }
        if (!primaryKeys.isEmpty()) {
            definitions.add("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }

        execute("CREATE TABLE IF NOT EXISTS " + table(table) + " (" + String.join(", ", definitions)
                + ") DEFAULT CHARSET = utf8mb4");
    }

    /**
     * Ajoute une colonne à une table existante si elle n'y est pas encore : createTable ne modifie jamais
     * une table déjà créée. La colonne doit accepter NULL (les lignes existantes n'ont pas de valeur).
     */
    public void addColumn(String table, Column column) {
        boolean exists = !query("SELECT 1 FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                (tablePrefix + table).toLowerCase(Locale.ROOT), column.getName().toLowerCase(Locale.ROOT)).isEmpty();
        if (!exists) {
            execute("ALTER TABLE " + table(table) + " ADD COLUMN " + columnDefinition(column));
        }
    }

    /** Retourne les lignes correspondant à where (AND entre les colonnes, map vide = toutes). */
    public List<Row> get(String table, Map<String, ?> where) {
        List<Object> params = new ArrayList<>();
        return query("SELECT * FROM " + table(table) + where(where, params), params.toArray());
    }

    public Optional<Row> getFirst(String table, Map<String, ?> where) {
        return get(table, where).stream().findFirst();
    }

    public void insert(String table, Map<String, ?> values) {
        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        values.forEach((column, value) -> {
            columns.add(id(column));
            params.add(value);
        });
        execute(insertSql(table, columns), params.toArray());
    }

    /** Insère la ligne, ou la met à jour si une ligne avec les mêmes keys existe déjà. */
    public void set(String table, Map<String, ?> values, String... keys) {
        if (keys.length == 0) {
            throw new IllegalArgumentException("set() nécessite au moins une colonne clé");
        }

        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        values.forEach((column, value) -> {
            columns.add(id(column));
            params.add(value);
        });

        List<String> keyColumns = Arrays.stream(keys).map(this::id).toList();
        if (!columns.containsAll(keyColumns)) {
            throw new IllegalArgumentException("Les clés " + keyColumns + " doivent faire partie des valeurs");
        }

        execute(upsertSql(table, columns, keyColumns), params.toArray());
    }

    public int update(String table, Map<String, ?> values, Map<String, ?> where) {
        StringJoiner set = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        values.forEach((column, value) -> {
            set.add(id(column) + " = ?");
            params.add(value);
        });
        return execute("UPDATE " + table(table) + " SET " + set + where(where, params), params.toArray());
    }

    public int delete(String table, Map<String, ?> where) {
        List<Object> params = new ArrayList<>();
        return execute("DELETE FROM " + table(table) + where(where, params), params.toArray());
    }

    /** Requête brute, pour les cas non couverts. */
    public List<Row> query(String sql, Object... params) {
        try (Connection connection = this.sql.getConnection();
             PreparedStatement statement = prepare(connection, sql, params);
             ResultSet result = statement.executeQuery()) {

            ResultSetMetaData meta = result.getMetaData();
            List<Row> rows = new ArrayList<>();
            while (result.next()) {
                Map<String, Object> values = new HashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    values.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), result.getObject(i));
                }
                rows.add(new Row(values));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Erreur SQL : " + sql, e);
        }
    }

    /** Instruction brute, pour les cas non couverts. */
    public int execute(String sql, Object... params) {
        try (Connection connection = this.sql.getConnection();
             PreparedStatement statement = prepare(connection, sql, params)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Erreur SQL : " + sql, e);
        }
    }

    private String columnDefinition(Column column) {
        return id(column.getName()) + " " + columnType(column) + (column.isNotNull() || column.isPrimaryKey() ? " NOT NULL" : "");
    }

    private String columnType(Column column) {
        return switch (column.getType()) {
            case INT -> "INT";
            case LONG -> "BIGINT";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case STRING -> "VARCHAR(" + column.getLength() + ")";
            case TEXT -> "TEXT";
            // Jusqu'à 4 Go : un inventaire plein de shulkers et de livres dépasse vite les 64 Ko d'un BLOB simple
            case BLOB -> "LONGBLOB";
            case UUID -> "CHAR(36)";
        };
    }

    private String insertSql(String table, List<String> columns) {
        return "INSERT INTO " + table(table) + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
    }

    /**
     * Seule syntaxe qui diffère entre les deux bases :
     * MySQL utilise l'alias "AS new" (VALUES() y est déprécié), MariaDB ne connaît que VALUES().
     */
    private String upsertSql(String table, List<String> columns, List<String> keys) {
        String valueFormat = sql.isMariaDb() ? "VALUES(%s)" : "new.%s";
        StringJoiner updates = new StringJoiner(", ");
        for (String column : columns) {
            if (!keys.contains(column)) {
                updates.add(column + " = " + valueFormat.formatted(column));
            }
        }
        // Aucune colonne hors clé : on réaffecte la clé à elle-même pour ne rien changer
        String update = updates.length() == 0 ? keys.getFirst() + " = " + keys.getFirst() : updates.toString();
        return insertSql(table, columns) + (sql.isMariaDb() ? "" : " AS new") + " ON DUPLICATE KEY UPDATE " + update;
    }

    /**
     * Nom réel d'une table, avec le préfixe du plugin, pour ne pas entrer en conflit
     * avec les tables d'autres plugins dans la même base. À utiliser dans les requêtes brutes.
     */
    public String table(String name) {
        return id(tablePrefix + name);
    }

    /** Valide et entoure un nom de table/colonne ; empêche toute injection via les noms. */
    private String id(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(lower).matches()) {
            throw new IllegalArgumentException("Nom de table/colonne invalide : " + name);
        }
        return "`" + lower + "`";
    }

    private String where(Map<String, ?> where, List<Object> params) {
        if (where == null || where.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(" AND ", " WHERE ", "");
        where.forEach((column, value) -> {
            if (value == null) {
                joiner.add(id(column) + " IS NULL");
            } else {
                joiner.add(id(column) + " = ?");
                params.add(value);
            }
        });
        return joiner.toString();
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                Object value = switch (params[i]) {
                    case UUID uuid -> uuid.toString();
                    case Enum<?> e -> e.name();
                    case null, default -> params[i];
                };
                statement.setObject(i + 1, value);
            }
            return statement;
        } catch (SQLException e) {
            statement.close();
            throw e;
        }
    }
}
