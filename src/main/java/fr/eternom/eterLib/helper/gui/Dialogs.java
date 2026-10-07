package fr.eternom.eterLib.helper.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Fenêtres natives de Minecraft (Dialogs, client 1.21.6+) à deux boutons, pour la saisie (texte, nombre) et les
 * confirmations. Le plugin construit le contenu (DialogBase : titre, textes, champs) ; ici, les boutons :
 * un clic n'est accepté qu'une fois, pendant 5 minutes, et la réponse est traitée sur le thread principal,
 * seulement si le joueur est toujours connecté.
 */
public final class Dialogs {

    private static final ClickCallback.Options ONE_USE = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(5))
            .build();

    private Dialogs() {
    }

    /** Affiche base avec les boutons confirm (onConfirm reçoit les champs saisis) et cancel. */
    public static void show(JavaPlugin plugin, Player player, DialogBase base, Component confirm, Component cancel,
                            Consumer<DialogResponseView> onConfirm, Runnable onCancel) {
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.confirmation(
                        button(plugin, player, confirm, onConfirm),
                        button(plugin, player, cancel, response -> onCancel.run())))));
    }

    private static ActionButton button(JavaPlugin plugin, Player player, Component label, Consumer<DialogResponseView> onClick) {
        return ActionButton.builder(label)
                .action(DialogAction.customClick(
                        // Le clic arrive du réseau : on repasse sur le thread principal
                        (response, audience) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) {
                                onClick.accept(response);
                            }
                        }),
                        ONE_USE))
                .build();
    }
}
