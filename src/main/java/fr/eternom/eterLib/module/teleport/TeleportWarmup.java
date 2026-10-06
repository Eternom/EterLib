package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Attente avant téléportation : bossbar qui se vide, particules, et annulation si le joueur bouge.
 * Une seule attente par joueur : en relancer une remplace la précédente.
 * Thread principal uniquement.
 */
public class TeleportWarmup {

    private static final int PERIOD_TICKS = 2;
    /** Déplacement toléré (au carré) : environ un demi-bloc ; tourner la tête n'annule pas. */
    private static final double MAX_MOVE_SQUARED = 0.25;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final int warmupTicks;
    private final boolean cancelOnMove;
    private final Map<UUID, Running> running = new HashMap<>();

    public TeleportWarmup(JavaPlugin plugin, Messages messages, Duration warmup, boolean cancelOnMove) {
        this.plugin = plugin;
        this.messages = messages;
        this.warmupTicks = (int) (warmup.toMillis() / 50);
        this.cancelOnMove = cancelOnMove;
    }

    /** Lance l'attente puis onComplete ; immédiat si aucune attente n'est configurée ou si le joueur en est dispensé. */
    public void start(Player player, String label, Runnable onComplete) {
        cancel(player, null);
        if (warmupTicks <= 0 || player.hasPermission(TeleportService.BYPASS_WARMUP)) {
            onComplete.run();
            return;
        }
        messages.actionBar(player, "teleport.warmup-start", "time", seconds(warmupTicks));
        running.put(player.getUniqueId(), new Running(player, label, onComplete));
    }

    /** Annule l'attente en cours ; reasonKey = message à afficher, ou null pour annuler sans rien dire. */
    public void cancel(Player player, String reasonKey) {
        Running warmup = running.remove(player.getUniqueId());
        if (warmup == null) {
            return;
        }
        warmup.stop();
        if (reasonKey != null && player.isOnline()) {
            messages.send(player, reasonKey);
            TeleportEffects.cancelled(player);
            BossBar bar = BossBar.bossBar(messages.get(player, "teleport.cancelled-bar"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
            Bukkit.getScheduler().runTaskLater(plugin, () -> player.hideBossBar(bar), 20);
        }
    }

    private String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1f", ticks / 20.0);
    }

    private final class Running {

        private final Player player;
        private final String label;
        private final Runnable onComplete;
        private final Location start;
        private final BossBar bar;
        private final BukkitTask task;
        private int elapsed;

        Running(Player player, String label, Runnable onComplete) {
            this.player = player;
            this.label = label;
            this.onComplete = onComplete;
            this.start = player.getLocation();
            this.bar = BossBar.bossBar(title(warmupTicks), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
            this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0, PERIOD_TICKS);
        }

        private void tick() {
            if (!player.isOnline()) {
                cancel(player, null);
                return;
            }
            if (cancelOnMove && moved()) {
                cancel(player, "teleport.cancelled-move");
                return;
            }

            elapsed += PERIOD_TICKS;
            double progress = Math.min(1, (double) elapsed / warmupTicks);
            bar.progress((float) (1 - progress));
            bar.name(title(warmupTicks - elapsed));
            TeleportEffects.warmup(player, elapsed, progress);
            if (elapsed % 20 == 0) {
                TeleportEffects.countdown(player, progress);
            }

            if (elapsed >= warmupTicks) {
                running.remove(player.getUniqueId());
                stop();
                onComplete.run();
            }
        }

        private boolean moved() {
            Location now = player.getLocation();
            return now.getWorld() != start.getWorld() || now.distanceSquared(start) > MAX_MOVE_SQUARED;
        }

        private Component title(int ticksLeft) {
            return messages.get(player, "teleport.warmup-bar", "destination", label, "time", seconds(Math.max(0, ticksLeft)));
        }

        void stop() {
            task.cancel();
            player.hideBossBar(bar);
        }
    }
}
