package fr.eternom.eterLib.module.action;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.action.Actions.Argument;
import fr.eternom.eterLib.module.combat.CombatTracker;
import fr.eternom.eterLib.module.teleport.TeleportService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Les actions et conditions d'EterLib, utilisables par tous (PNJ, menus...) :
 * <pre>
 *   eterlib:message <texte>            message (MiniMessage, avec le préfixe)
 *   eterlib:actionbar <texte>          au-dessus de la barre d'objets
 *   eterlib:title <titre> [sous-titre]
 *   eterlib:command <commande>         lancée par le joueur (sans /)
 *   eterlib:console <commande>         lancée par la console ({player} = le pseudo)
 *   eterlib:sound <son> [volume] [hauteur]
 *   eterlib:server <serveur>           envoie le joueur sur un serveur (refusé en combat)
 *   eterlib:close                      ferme le menu ouvert
 *   ?eterlib:permission <permission>   ?eterlib:on-server <nom>   ?eterlib:world <début du nom>   ?eterlib:gamemode <mode>
 * </pre>
 */
final class BuiltinActions {

    private BuiltinActions() {
    }

    static void register(Actions actions, JavaPlugin lib, Messages messages, TeleportService teleports, CombatTracker combat) {
        actions.register(lib, "eterlib:message", "Message au joueur (MiniMessage)", List.of(Argument.required("texte")),
                (player, args) -> player.sendMessage(messages.prefix().append(render(messages, args))));
        actions.register(lib, "eterlib:actionbar", "Texte au-dessus de la barre d'objets", List.of(Argument.required("texte")),
                (player, args) -> player.sendActionBar(render(messages, args)));
        actions.register(lib, "eterlib:title", "Titre à l'écran", List.of(Argument.required("titre"), Argument.optional("sous-titre")),
                (player, args) -> player.showTitle(Title.title(messages.render(args.getFirst(), TagResolver.empty()),
                        args.size() > 1 ? messages.render(args.get(1), TagResolver.empty()) : Component.empty())));
        actions.register(lib, "eterlib:command", "Commande lancée par le joueur (sans /)", List.of(Argument.required("commande")),
                (player, args) -> player.performCommand(String.join(" ", args)));
        actions.register(lib, "eterlib:console", "Commande lancée par la console ({player} = le pseudo)", List.of(Argument.required("commande")),
                (player, args) -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.join(" ", args)));
        actions.register(lib, "eterlib:sound", "Son joué au joueur", List.of(Argument.required("son", BuiltinActions::sounds),
                Argument.optional("volume"), Argument.optional("hauteur")), (player, args) -> {
            float volume = args.size() > 1 ? number(args.get(1), 1) : 1;
            float pitch = args.size() > 2 ? number(args.get(2), 1) : 1;
            player.playSound(Sound.sound(Key.key(args.getFirst().toLowerCase(Locale.ROOT)), Sound.Source.MASTER, volume, pitch));
        });
        actions.register(lib, "eterlib:server", "Envoie le joueur sur un serveur", List.of(Argument.required("serveur")), (player, args) -> {
            long fighting = combat.remainingSeconds(player.getUniqueId());
            if (fighting > 0) {
                messages.send(player, "teleport.in-combat", "time", String.valueOf(fighting));
                return;
            }
            messages.send(player, "teleport.sending", "server", args.getFirst());
            teleports.connect(player, args.getFirst());
        });
        actions.register(lib, "eterlib:close", "Ferme le menu ouvert", List.of(), (player, args) -> player.closeInventory());

        actions.registerCondition(lib, "eterlib:permission", "Le joueur a cette permission", List.of(Argument.required("permission")),
                (player, args) -> player.hasPermission(args.getFirst()));
        actions.registerCondition(lib, "eterlib:on-server", "Le joueur est sur ce serveur (début du nom)", List.of(Argument.required("serveur")),
                (player, args) -> serverName(lib).toLowerCase(Locale.ROOT).startsWith(args.getFirst().toLowerCase(Locale.ROOT)));
        actions.registerCondition(lib, "eterlib:world", "Le joueur est dans ce monde (début du nom)", List.of(Argument.required("monde")),
                (player, args) -> player.getWorld().getName().toLowerCase(Locale.ROOT).startsWith(args.getFirst().toLowerCase(Locale.ROOT)));
        actions.registerCondition(lib, "eterlib:gamemode", "Le joueur est dans ce mode de jeu",
                List.of(Argument.required("mode", typed -> Arrays.stream(GameMode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT)).toList())),
                (player, args) -> player.getGameMode().name().equalsIgnoreCase(args.getFirst()));
    }

    private static Component render(Messages messages, List<String> args) {
        return messages.render(String.join(" ", args), TagResolver.empty());
    }

    private static String serverName(JavaPlugin lib) {
        return lib.getConfig().getString("server-name", "");
    }

    private static List<String> sounds(String typed) {
        return Registry.SOUNDS.stream().map(sound -> Registry.SOUNDS.getKey(sound))
                .filter(java.util.Objects::nonNull).map(key -> key.getKey())
                .filter(key -> key.startsWith(typed)).limit(30).toList();
    }

    private static float number(String text, float fallback) {
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
