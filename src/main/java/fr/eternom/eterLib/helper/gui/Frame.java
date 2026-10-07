package fr.eternom.eterLib.helper.gui;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.function.IntPredicate;

/**
 * Cadre commun des menus : vitres grises, et une équerre de la couleur d'accent dans chaque coin (orange pour un joueur,
 * rouge pour une vue admin). À dessiner avant de poser les boutons, qui recouvrent les vitres.
 */
public final class Frame {

    private Frame() {
    }

    /** La bordure seule (première et dernière lignes, colonnes de gauche et de droite). */
    public static void draw(Inventory inventory, Material accent) {
        int rows = inventory.getSize() / 9;
        fillWhere(inventory, accent, slot -> slot / 9 != 0 && slot / 9 != rows - 1 && slot % 9 != 0 && slot % 9 != 8);
    }

    /** Toutes les cases sauf content (menus dont le contenu n'occupe qu'une partie de l'intérieur). */
    public static void fill(Inventory inventory, Material accent, Collection<Integer> content) {
        fillWhere(inventory, accent, content::contains);
    }

    private static void fillWhere(Inventory inventory, Material accent, IntPredicate empty) {
        ItemStack accentPane = Items.pane(accent);
        ItemStack neutralPane = Items.pane(Material.GRAY_STAINED_GLASS_PANE);
        int rows = inventory.getSize() / 9;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!empty.test(slot)) {
                inventory.setItem(slot, corner(slot, rows) ? accentPane : neutralPane);
            }
        }
    }

    /** Équerre de 3 cases dans chaque coin : les 2 cases du bord haut ou bas, et celle du côté juste à côté. */
    private static boolean corner(int slot, int rows) {
        int row = slot / 9;
        int column = slot % 9;
        boolean edgeRow = row == 0 || row == rows - 1;
        boolean nextToEdgeRow = row == 1 || row == rows - 2;
        boolean edgeColumn = column == 0 || column == 8;
        boolean nextToEdgeColumn = column == 1 || column == 7;
        return (edgeRow && (edgeColumn || nextToEdgeColumn)) || (nextToEdgeRow && edgeColumn);
    }
}
