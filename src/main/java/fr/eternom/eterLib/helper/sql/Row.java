package fr.eternom.eterLib.helper.sql;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ligne de résultat. Les getters typés lissent les différences entre bases
 * (ex : un BOOLEAN MySQL est un TINYINT, qui peut revenir en Integer).
 */
public final class Row {

    private final Map<String, Object> values;

    Row(Map<String, Object> values) {
        this.values = values;
    }

    public Object get(String column) {
        return values.get(column.toLowerCase(Locale.ROOT));
    }

    public String getString(String column) {
        Object value = get(column);
        return value == null ? null : value.toString();
    }

    public int getInt(String column) {
        Object value = get(column);
        if (value == null) return 0;
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }

    public long getLong(String column) {
        Object value = get(column);
        if (value == null) return 0L;
        return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    }

    public float getFloat(String column) {
        Object value = get(column);
        if (value == null) return 0F;
        return value instanceof Number n ? n.floatValue() : Float.parseFloat(value.toString());
    }

    public double getDouble(String column) {
        Object value = get(column);
        if (value == null) return 0D;
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
    }

    public boolean getBoolean(String column) {
        Object value = get(column);
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }

    public UUID getUUID(String column) {
        Object value = get(column);
        if (value == null) return null;
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString().trim());
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
