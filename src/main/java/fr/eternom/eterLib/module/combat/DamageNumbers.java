package fr.eternom.eterLib.module.combat;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chiffres de dégâts flottants, au choix du joueur (/combat numbers, PDC eterlib:damage_numbers ; désactivés par
 * défaut) : à chaque coup qu'il donne, le nombre apparaît au-dessus de la cible, monte et disparaît en 1 s. Vu par lui
 * seul. Couleur : blanc physique, bleu magique, gris brut, or critique ; « Esquivé » quand la cible esquive.
 */
public final class DamageNumbers implements Listener {

    private static final NamespacedKey KEY = new NamespacedKey("eterlib", "damage_numbers");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final fr.eternom.eterLib.helper.message.Messages messages;

    public DamageNumbers(JavaPlugin plugin, fr.eternom.eterLib.helper.message.Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public static boolean enabled(Player player) {
        return player.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    /** Change le choix ; true s'ils sont maintenant affichés. */
    public static boolean toggle(Player player) {
        if (enabled(player)) {
            player.getPersistentDataContainer().remove(KEY);
            return false;
        }
        player.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(CombatHitEvent event) {
        if (!(event.getAttacker() instanceof Player player) || !enabled(player) || event.getVictim() == player) {
            return;
        }
        String text;
        if (event.isDodged()) {
            text = "<gray><i>" + messages.plain(player, "combat.player.dodged");
        } else {
            String value = String.format(Locale.ROOT, event.getDamage() >= 10 ? "%.0f" : "%.1f", event.getDamage());
            String color = event.isCrit() ? "<gold><b>" : switch (event.getKind()) {
                case MAGIC -> "<aqua>";
                case TRUE -> "<gray>";
                case PHYSICAL -> "<white>";
            };
            text = color + value + (event.isCrit() ? " !" : "");
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = event.getVictim().getLocation().add(random.nextDouble(-0.4, 0.4), event.getVictim().getHeight() + 0.3,
                random.nextDouble(-0.4, 0.4));
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, spawned -> {
            spawned.text(MINI.deserialize(text));
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setPersistent(false);
            spawned.setVisibleByDefault(false);
            spawned.setShadowed(true);
            spawned.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
        });
        player.showEntity(plugin, display);
        // Monte de 0,8 bloc en 1 s (interpolation du client), puis disparaît
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (display.isValid()) {
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(18);
                display.setTransformation(new Transformation(new Vector3f(0, 0.8f, 0), display.getTransformation().getLeftRotation(),
                        display.getTransformation().getScale(), display.getTransformation().getRightRotation()));
            }
        }, 2);
        plugin.getServer().getScheduler().runTaskLater(plugin, display::remove, 22);
    }
}
