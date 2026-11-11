package fr.eternom.eterLib.helper.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Affichage compact des menus (petits écrans : mobile, console) : à l'ouverture d'un menu, une description de plus de
 * MAX lignes est réduite à ses 2 premières lignes de texte et à sa dernière (ce que fait le clic). Choix du joueur, gardé
 * sur lui (PDC eterlib:compact_menus) ; par défaut, compact pour les joueurs Bedrock (uuid Floodgate 00000000-...).
 * Appliqué par MenuListener à tous les menus Eter ; un item redessiné après l'ouverture n'est pas réduit.
 */
public final class Compact {

    private static final NamespacedKey KEY = new NamespacedKey("eterlib", "compact_menus");
    private static final int MAX = 4;

    private Compact() {
    }

    /** Le joueur veut l'affichage compact (son choix, sinon : oui sur Bedrock). */
    public static boolean enabled(Player player) {
        Byte chosen = player.getPersistentDataContainer().get(KEY, PersistentDataType.BYTE);
        return chosen != null ? chosen == 1 : player.getUniqueId().getMostSignificantBits() == 0;
    }

    /** Change le choix du joueur ; true s'il est maintenant en compact. */
    public static boolean toggle(Player player) {
        boolean now = !enabled(player);
        player.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) (now ? 1 : 0));
        return now;
    }

    /** Réduit les descriptions trop longues de ce menu. */
    static void apply(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null) {
                continue;
            }
            List<Component> lore = item.getItemMeta().lore();
            List<Component> text = lore.stream().filter(line -> !line.equals(Component.empty())).toList();
            if (text.size() <= MAX) {
                continue;
            }
            List<Component> shorter = new ArrayList<>(text.subList(0, 2));
            shorter.add(text.getLast());
            item.editMeta(meta -> meta.lore(shorter));
            inventory.setItem(slot, item);
        }
    }
}
