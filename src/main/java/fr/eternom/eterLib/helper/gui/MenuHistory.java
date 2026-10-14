package fr.eternom.eterLib.helper.gui;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le chemin de chaque joueur dans les menus de tous les plugins Eter, pour que le bouton Retour ramène là d'où il vient
 * vraiment (un menu staff, un sous-menu de /menu, un autre plugin...) au lieu d'une destination fixe ; un menu ouvert
 * directement (par sa commande) a un bouton Fermer.
 *
 * Un menu ouvert depuis un autre, ou moins de 2 s après en avoir fermé un (le temps d'une lecture en base), prolonge le
 * chemin ; sinon le chemin repart de lui. Revenir sur un menu du chemin coupe ce qui le suit ; un menu de la même sorte
 * que le dernier (autre page, menu rafraîchi après une action) le remplace. Le bouton (repéré par une étiquette sur
 * l'item) est redessiné à l'ouverture, quand le chemin est connu. Thread principal.
 */
public final class MenuHistory implements Listener {

    /** Délai entre la fermeture d'un menu et l'ouverture du suivant pour qu'ils restent sur le même chemin. */
    private static final long LINK_MILLIS = 2000;

    private static final class Path {
        private final Deque<Menu> menus = new ArrayDeque<>();
        private long closedAt;
    }

    private final Map<UUID, Path> paths = new ConcurrentHashMap<>();
    private final NamespacedKey backKey;
    private final Messages messages;

    public MenuHistory(NamespacedKey backKey, Messages messages) {
        this.backKey = backKey;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Path path = paths.computeIfAbsent(player.getUniqueId(), uuid -> new Path());
        boolean linked = player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu
                || System.currentTimeMillis() - path.closedAt < LINK_MILLIS;
        if (!linked) {
            path.menus.clear();
        }
        if (path.menus.contains(menu)) {
            while (path.menus.peekLast() != menu) {
                path.menus.pollLast();
            }
        } else {
            if (!path.menus.isEmpty() && path.menus.peekLast().getClass() == menu.getClass()) {
                path.menus.pollLast();
            }
            path.menus.addLast(menu);
        }
        repaint(player, event.getInventory());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Path path = paths.get(event.getPlayer().getUniqueId());
        if (path != null && event.getInventory().getHolder(false) instanceof Menu) {
            path.closedAt = System.currentTimeMillis();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        paths.remove(event.getPlayer().getUniqueId());
    }

    /** Le menu d'où vient le dernier menu ouvert ; null s'il a été ouvert directement. */
    public Menu previous(Player player) {
        Path path = paths.get(player.getUniqueId());
        if (path == null || path.menus.size() < 2) {
            return null;
        }
        Iterator<Menu> fromLast = path.menus.descendingIterator();
        fromLast.next();
        return fromLast.next();
    }

    /** true si le menu ouvert par le joueur est le dernier de son chemin (son bouton suit alors le chemin). */
    public boolean knows(Player player) {
        Path path = paths.get(player.getUniqueId());
        return path != null && !path.menus.isEmpty()
                && path.menus.peekLast() == player.getOpenInventory().getTopInventory().getHolder(false);
    }

    /** Le bouton selon le chemin : Retour s'il y a un menu précédent, Fermer sinon. */
    ItemStack button(Player viewer) {
        ItemStack item = previous(viewer) != null
                ? Items.item(Material.OAK_DOOR, messages.get(viewer, "menu.back"), List.of(messages.get(viewer, "menu.back-previous")))
                : Items.item(Material.BARRIER, messages.get(viewer, "menu.close"), List.of());
        return tag(item);
    }

    /** Marque un item comme bouton Retour, pour le retrouver à l'ouverture du menu. */
    ItemStack tag(ItemStack item) {
        item.editPersistentDataContainer(data -> data.set(backKey, PersistentDataType.BOOLEAN, true));
        return item;
    }

    private void repaint(Player player, Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && item.getPersistentDataContainer().has(backKey)) {
                inventory.setItem(slot, button(player));
            }
        }
    }
}
