package fr.eternom.eterLib.helper.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.InventoryHolder;

/**
 * Inventaire de menu (tout plugin Eter). Les clics y sont toujours annulés (on ne peut pas prendre les items),
 * puis transmis au menu au tick suivant par {@link MenuListener}.
 */
public interface Menu extends InventoryHolder {

    void onClick(Player player, int slot, ClickType click);
}
