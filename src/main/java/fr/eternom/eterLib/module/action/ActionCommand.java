package fr.eternom.eterLib.module.action;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /eterlib (staff, eterlib.admin) :
 * - actions [filtre] : les actions et conditions connues du réseau, avec leur plugin et leurs arguments ;
 * - action <ligne> [; ligne...] : lance une chaîne sur soi (pour essayer ce qu'on mettra dans un PNJ).
 * - state ... : les états (StateCommand).
 */
public class ActionCommand implements TabExecutor {

    private final Actions actions;
    private final Messages messages;

    private final fr.eternom.eterLib.module.state.StateCommand states;

    public ActionCommand(Actions actions, Messages messages, fr.eternom.eterLib.module.state.StateCommand states) {
        this.states = states;
        this.actions = actions;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "actions" -> {
                String filter = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                List<Actions.Definition> list = actions.known().stream().filter(definition -> definition.id().contains(filter)).toList();
                messages.send(sender, "action.list-title", "count", String.valueOf(list.size()));
                for (Actions.Definition definition : list) {
                    sender.sendMessage(messages.get(sender, actions.isLocal(definition.id()) ? "action.list-line" : "action.list-line-remote",
                            "usage", definition.usage(), "plugin", definition.plugin(), "description", definition.description()));
                }
            }
            case "action" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "command.players-only");
                    return true;
                }
                List<String> lines = lines(Arrays.copyOfRange(args, 1, args.length));
                List<String> errors = actions.validate(player, lines);
                if (lines.isEmpty() || !errors.isEmpty()) {
                    errors.forEach(error -> messages.send(player, "action.check-error", "problem", error));
                    if (lines.isEmpty()) {
                        messages.send(player, "action.usage");
                    }
                    return true;
                }
                actions.run(player, lines);
            }
            case "state" -> states.run(sender, args);
            default -> messages.send(sender, "action.usage");
        }
        return true;
    }

    /** « a:b x ; c:d » -> deux lignes. */
    static List<String> lines(String[] words) {
        List<String> lines = new ArrayList<>();
        for (String part : String.join(" ", words).split(";")) {
            if (!part.isBlank()) {
                lines.add(part.trim());
            }
        }
        return lines;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("actions", "action", "state").stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args[0].equalsIgnoreCase("state")) {
            return states.complete(args);
        }
        if (!args[0].equalsIgnoreCase("action")) {
            return List.of();
        }
        // Complétion de la dernière ligne de la chaîne (après le dernier « ; »)
        List<String> words = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        int last = words.lastIndexOf(";");
        return actions.complete(last < 0 ? words : words.subList(last + 1, words.size()));
    }
}
