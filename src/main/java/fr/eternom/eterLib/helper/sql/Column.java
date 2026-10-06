package fr.eternom.eterLib.helper.sql;

/**
 * Définition d'une colonne, traduite en type MySQL/MariaDB par Database#createTable.
 * Exemple : Column.of("uuid", Column.Type.UUID).primaryKey()
 */
public final class Column {

    public enum Type { INT, LONG, FLOAT, DOUBLE, BOOLEAN, STRING, TEXT, UUID }

    private final String name;
    private final Type type;
    private int length = 255;
    private boolean primaryKey;
    private boolean autoIncrement;
    private boolean notNull;

    private Column(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    public static Column of(String name, Type type) {
        return new Column(name, type);
    }

    /** Longueur max pour Type.STRING (255 par défaut). */
    public Column length(int length) {
        this.length = length;
        return this;
    }

    /** Plusieurs colonnes primaryKey() forment une clé composée. */
    public Column primaryKey() {
        this.primaryKey = true;
        return this;
    }

    /** Clé primaire auto-incrémentée ; à utiliser seule, sans autre primaryKey(). */
    public Column autoIncrement() {
        this.autoIncrement = true;
        this.primaryKey = true;
        return this;
    }

    public Column notNull() {
        this.notNull = true;
        return this;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public int getLength() {
        return length;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    public boolean isNotNull() {
        return notNull;
    }
}
