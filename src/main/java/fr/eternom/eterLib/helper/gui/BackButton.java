package fr.eternom.eterLib.helper.gui;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Bouton « Retour » ou « Fermer » d'un menu principal, réglé dans la config de chaque plugin
 * (menus.<menu>.back-command) :
 * - une commande : il l'exécute pour le joueur, ce qui relie les menus entre eux (ex : "pass" ramène au pass) ;
 * - vide (par défaut) : il ramène au menu central /menu (EterEssential) s'il existe sur ce serveur, sinon il ferme.
 *   Tous les menus se relient ainsi d'office : un joueur navigue partout sans taper de commande ;
 * - "close" : il ferme toujours.
 * Créé par {@code EterLib#backButton(String)} : ses textes sont ceux d'EterLib (lang/ > menu.*).
 */
public final class BackButton {

    /** Commande du menu central, visée par un bouton sans commande réglée. */
    private static final String MAIN_MENU = "menu";
    private static final String CLOSE = "close";

    private final String command; // "" : menu central ou fermer ; "close" : fermer
    private final Messages messages;

    public BackButton(String command, Messages messages) {
        String trimmed = command == null ? "" : command.trim();
        this.command = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        this.messages = messages;
    }

    /** La commande lancée au clic, "" pour fermer. Lue au moment du clic : /menu peut être chargé après ce plugin. */
    private String target() {
        if (command.equalsIgnoreCase(CLOSE)) {
            return "";
        }
        if (!command.isEmpty()) {
            return command;
        }
        return Bukkit.getCommandMap().getCommand(MAIN_MENU) != null ? MAIN_MENU : "";
    }

    public ItemStack item(Player viewer) {
        String target = target();
        if (target.isEmpty()) {
            return Items.item(Material.BARRIER, messages.get(viewer, "menu.close"), List.of());
        }
        return Items.item(Material.OAK_DOOR, messages.get(viewer, "menu.back"),
                List.of(messages.get(viewer, "menu.back-lore", "command", "/" + target)));
    }

    /** Ferme le menu, puis lance la commande s'il y en a une. */
    public void click(Player player) {
        Sounds.click(player);
        String target = target();
        player.closeInventory();
        if (!target.isEmpty()) {
            player.performCommand(target);
        }
    }
}
