package fr.eternom.eterLib.helper.gui;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Bouton « Retour » ou « Fermer » d'un menu principal, réglé dans la config de chaque plugin
 * (menus.<menu>.back-command) : avec une commande, il l'exécute pour le joueur, ce qui relie les menus entre eux
 * (ex : "profile" ramène au menu du profil) ; vide, il ferme simplement le menu.
 * Créé par {@code EterLib#backButton(String)} : ses textes sont ceux d'EterLib (lang/ > menu.*).
 */
public final class BackButton {

    private final String command; // "" : fermer
    private final Messages messages;

    public BackButton(String command, Messages messages) {
        String trimmed = command == null ? "" : command.trim();
        this.command = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        this.messages = messages;
    }

    public ItemStack item(Player viewer) {
        if (command.isEmpty()) {
            return Items.item(Material.BARRIER, messages.get(viewer, "menu.close"), List.of());
        }
        return Items.item(Material.OAK_DOOR, messages.get(viewer, "menu.back"),
                List.of(messages.get(viewer, "menu.back-lore", "command", "/" + command)));
    }

    /** Ferme le menu, puis lance la commande si elle est réglée. */
    public void click(Player player) {
        Sounds.click(player);
        player.closeInventory();
        if (!command.isEmpty()) {
            player.performCommand(command);
        }
    }
}
