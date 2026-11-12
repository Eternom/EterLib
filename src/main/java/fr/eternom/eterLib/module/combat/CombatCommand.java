package fr.eternom.eterLib.module.combat;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /combat : les statistiques de combat du joueur, en clair (armure et résistance magique avec la réduction qu'elles
 * donnent, critique, esquive, ténacité...). Toutes les lignes, même à 0 : le joueur voit ce qui existe. Le détail des
 * sources reste pour le staff (/eterlib stats).
 */
public class CombatCommand implements TabExecutor {

    private final CombatSystem combat;
    private final Messages messages;

    public CombatCommand(CombatSystem combat, Messages messages) {
        this.combat = combat;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("numbers")) {
            messages.send(player, DamageNumbers.toggle(player) ? "combat.player.numbers-on" : "combat.player.numbers-off");
            return true;
        }
        StatSheet sheet = combat.stats(player);
        messages.send(player, "combat.player.header");
        for (Stat stat : Stat.values()) {
            double total = sheet.get(stat);
            String value = stat.part() ? Math.round(total * 100) + " %" : String.valueOf(Math.round(total));
            String name = messages.plain(player, "combat.player.stat." + stat.id());
            if (stat == Stat.ARMOR || stat == Stat.MAGIC_RESIST) {
                player.sendMessage(messages.get(player, "combat.player.defense", "stat", name, "value", value,
                        "reduction", String.valueOf(Math.round(CombatMath.reduction(total, combat.constant()) * 100))));
            } else {
                player.sendMessage(messages.get(player, "combat.player.line", "stat", name, "value", value));
            }
        }
        messages.send(player, "combat.player.footer");
        player.sendMessage(messages.get(player, "combat.player.hint"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 && "numbers".startsWith(args[0].toLowerCase()) ? List.of("numbers") : List.of();
    }
}
