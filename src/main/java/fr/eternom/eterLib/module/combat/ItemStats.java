package fr.eternom.eterLib.module.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Les statistiques d'un objet (objets custom à venir) : une donnée cachée eterlib:stats, « armor=12;magic_resist=8;
 * crit_chance=0.05 ». Comptées pour une armure portée et pour l'objet en main (arme). L'équipement de Minecraft n'en a
 * pas : il ne donne que son armure, convertie.
 */
public final class ItemStats {

    private static final NamespacedKey KEY = new NamespacedKey("eterlib", "stats");

    private ItemStats() {
    }

    public static Map<Stat, Double> read(ItemStack item) {
        Map<Stat, Double> stats = new EnumMap<>(Stat.class);
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return stats;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (raw == null) {
            return stats;
        }
        for (String part : raw.split(";")) {
            String[] pair = part.split("=", 2);
            Stat stat = pair.length == 2 ? Stat.parse(pair[0]) : null;
            if (stat == null) {
                continue;
            }
            try {
                stats.merge(stat, Double.parseDouble(pair[1].trim()), Double::sum);
            } catch (NumberFormatException ignored) {
                // valeur illisible : ignorée
            }
        }
        return stats;
    }

    /** Écrit les statistiques d'un objet (remplace les précédentes). */
    public static void write(ItemStack item, Map<Stat, Double> stats) {
        StringJoiner joiner = new StringJoiner(";");
        stats.forEach((stat, value) -> joiner.add(stat.id() + "=" + value));
        item.editMeta(meta -> {
            if (stats.isEmpty()) {
                meta.getPersistentDataContainer().remove(KEY);
            } else {
                meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, joiner.toString());
            }
        });
    }
}
