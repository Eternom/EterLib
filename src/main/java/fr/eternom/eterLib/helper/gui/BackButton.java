package fr.eternom.eterLib.helper.gui;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Bouton « Retour » ou « Fermer » en bas d'un menu. Il suit le chemin du joueur ({@link MenuHistory}) : il rouvre le
 * menu d'où il vient (même d'un autre plugin, même un menu staff), et ferme un menu ouvert directement par sa commande.
 *
 * La commande réglée dans la config de chaque plugin (menus.&lt;menu&gt;.back-command) ne sert plus que si le chemin
 * est inconnu : une commande à lancer, vide pour le menu central /menu (sinon fermer), "close" pour fermer.
 * Créé par {@code EterLib#backButton(String)} : ses textes sont ceux d'EterLib (lang/ > menu.*).
 */
public final class BackButton {

    /** Commande du menu central, visée par un bouton sans commande réglée. */
    private static final String MAIN_MENU = "menu";
    private static final String CLOSE = "close";

    private final String command; // "" : menu central ou fermer ; "close" : fermer
    private final Messages messages;
    private final MenuHistory history;

    public BackButton(String command, Messages messages, MenuHistory history) {
        String trimmed = command == null ? "" : command.trim();
        this.command = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        this.messages = messages;
        this.history = history;
    }

    /** La commande de secours lancée au clic, "" pour fermer. Lue au moment du clic : /menu peut être chargé après ce plugin. */
    private String target() {
        if (command.equalsIgnoreCase(CLOSE)) {
            return "";
        }
        if (!command.isEmpty()) {
            return command;
        }
        return Bukkit.getCommandMap().getCommand(MAIN_MENU) != null ? MAIN_MENU : "";
    }

    /**
     * Le bouton. Pour un menu qui s'ouvre, il est redessiné à l'ouverture selon le chemin ; pour un menu déjà ouvert
     * qui se redessine, il suit tout de suite le chemin.
     */
    public ItemStack item(Player viewer) {
        if (history.knows(viewer)) {
            return history.button(viewer);
        }
        String target = target();
        if (target.isEmpty()) {
            return history.tag(Items.item(Material.BARRIER, messages.get(viewer, "menu.close"), List.of()));
        }
        return history.tag(Items.item(Material.OAK_DOOR, messages.get(viewer, "menu.back"),
                List.of(messages.get(viewer, "menu.back-lore", "command", "/" + target))));
    }

    /** Rouvre le menu précédent, ferme un menu ouvert directement ; chemin inconnu : la commande de secours. */
    public void click(Player player) {
        Sounds.click(player);
        if (history.knows(player)) {
            Menu previous = history.previous(player);
            if (previous != null) {
                previous.reopen(player);
            } else {
                player.closeInventory();
            }
            return;
        }
        String target = target();
        player.closeInventory();
        if (!target.isEmpty()) {
            player.performCommand(target);
        }
    }
}
