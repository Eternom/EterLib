package fr.eternom.eterLib.module.state;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /eterlib state (staff) : sur un joueur nommé, sinon sur l'entité visée (10 blocs), sinon sur soi.
 * <pre>
 *   state types                                   les états connus et leur poids
 *   state list [joueur]                           les états actifs
 *   state apply <état> <puissance> <secondes> [joueur]
 *   state clear [joueur]
 * </pre>
 */
public final class StateCommand {

    private final States states;
    private final Messages messages;

    public StateCommand(States states, Messages messages) {
        this.states = states;
        this.messages = messages;
    }

    /** args[0] = « state ». */
    public void run(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "types" -> states.types().forEach(type -> messages.send(sender, "state.type-line", "id", type.id(),
                    "category", type.category().name().toLowerCase(Locale.ROOT), "weight", format(type.weight()),
                    "stacks", String.valueOf(type.maxStacks())));
            case "list" -> {
                LivingEntity target = target(sender, args.length > 2 ? args[2] : null);
                if (target == null) {
                    return;
                }
                List<StateInstance> active = states.active(target);
                messages.send(sender, "state.list-header", "target", target.getName(), "count", String.valueOf(active.size()),
                        "tenacity", format(states.tenacity(target)));
                long now = System.currentTimeMillis();
                active.forEach(state -> messages.send(sender, "state.list-line", "id", state.type().id(),
                        "power", format(state.power()), "stacks", String.valueOf(state.stacks()),
                        "time", format(state.remainingMillis(now) / 1000.0)));
            }
            case "apply" -> {
                if (args.length < 5) {
                    messages.send(sender, "state.usage");
                    return;
                }
                LivingEntity target = target(sender, args.length > 5 ? args[5] : null);
                Double power = number(args[3]);
                Double seconds = number(args[4]);
                if (target == null || power == null || seconds == null) {
                    if (power == null || seconds == null) {
                        messages.send(sender, "state.usage");
                    }
                    return;
                }
                if (states.type(args[2]) == null) {
                    messages.send(sender, "state.unknown", "state", args[2]);
                    return;
                }
                boolean done = states.apply(target, args[2].toLowerCase(Locale.ROOT), power, seconds, sender instanceof Entity entity ? entity : null);
                messages.send(sender, done ? "state.applied" : "state.refused", "state", args[2], "target", target.getName());
            }
            case "clear" -> {
                LivingEntity target = target(sender, args.length > 2 ? args[2] : null);
                if (target != null) {
                    messages.send(sender, "state.cleared", "count", String.valueOf(states.clear(target, StateExpireEvent.Reason.REMOVED)),
                            "target", target.getName());
                }
            }
            default -> messages.send(sender, "state.usage");
        }
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return filter(List.of("types", "list", "apply", "clear"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("apply")) {
            return filter(states.types().stream().map(StateType::id).toList(), args[2]);
        }
        boolean playerArg = (args.length == 3 && (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("clear")))
                || (args.length == 6 && args[1].equalsIgnoreCase("apply"));
        if (playerArg) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[args.length - 1]);
        }
        return List.of();
    }

    private LivingEntity target(CommandSender sender, String name) {
        if (name != null) {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) {
                messages.send(sender, "state.no-target");
            }
            return player;
        }
        if (sender instanceof Player player) {
            Entity looked = player.getTargetEntity(10);
            return looked instanceof LivingEntity living ? living : player;
        }
        messages.send(sender, "state.no-target");
        return null;
    }

    private static Double number(String text) {
        try {
            double value = Double.parseDouble(text.replace(',', '.'));
            return Double.isFinite(value) && value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }

    private static List<String> filter(List<String> options, String start) {
        String lower = start.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
