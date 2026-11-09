package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Attente avant téléportation : bossbar qui se vide, compte à rebours à l'écran, particules, et annulation si le joueur
 * bouge (quand c'est demandé : pas pour un tpa accepté par un autre). Une seule attente par joueur : une seconde
 * téléportation pendant l'attente est refusée, avec un message (jamais remplacée en silence).
 * Durée : teleport.warmup, ou moins avec la permission eter.teleport.warmup.<secondes> (la plus petite l'emporte, ex :
 * un grade avec eter.teleport.warmup.1). Thread principal uniquement.
 */
public class TeleportWarmup {

    public static final String PERMISSION_PREFIX = "eter.teleport.warmup.";
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

    /** Lance l'attente puis onComplete (règles de la config). Ancienne forme : une attente en cours est remplacée. */
    public void start(Player player, String label, Runnable onComplete) {
        cancel(player, null);
        start(player, label, null, onComplete, () -> { });
    }

    /**
     * Lance l'attente puis onComplete ; immédiat sans attente configurée ou avec eter.bypass.warmup. moveCancels null =
     * la config. onCancelled : l'attente a été annulée (mouvement, dégâts). false si une attente est déjà en cours
     * (le joueur est prévenu, rien n'est lancé).
     */
    public boolean start(Player player, String label, Boolean moveCancels, Runnable onComplete, Runnable onCancelled) {
        if (running.containsKey(player.getUniqueId())) {
            messages.send(player, "teleport.busy");
            return false;
        }
        int ticks = ticksFor(player);
        if (ticks <= 0) {
            onComplete.run();
            return true;
        }
        messages.actionBar(player, "teleport.warmup-start", "time", seconds(ticks));
        running.put(player.getUniqueId(), new Running(player, label, ticks, moveCancels == null ? cancelOnMove : moveCancels,
                onComplete, onCancelled));
        return true;
    }

    public boolean isRunning(UUID player) {
        return running.containsKey(player);
    }

    /** L'attente de ce joueur, selon sa permission (la plus petite eter.teleport.warmup.<n>) ; 0 = sans attente. */
    public int ticksFor(Player player) {
        if (warmupTicks <= 0 || player.hasPermission(TeleportService.BYPASS_WARMUP)) {
            return 0;
        }
        int best = warmupTicks;
        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            String name = permission.getPermission();
            if (permission.getValue() && name.startsWith(PERMISSION_PREFIX)) {
                try {
                    best = Math.min(best, Math.max(0, Integer.parseInt(name.substring(PERMISSION_PREFIX.length())) * 20));
                } catch (NumberFormatException ignored) {
                    // permission mal écrite : ignorée
                }
            }
        }
        return best;
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
        if (reasonKey != null) {
            warmup.onCancelled.run();
        }
    }

    private String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1f", ticks / 20.0);
    }

    private final class Running {

        private final Player player;
        private final String label;
        private final int totalTicks;
        private final boolean moveCancels;
        private final Runnable onComplete;
        private final Runnable onCancelled;
        private final Location start;
        private final BossBar bar;
        private final BukkitTask task;
        private int elapsed;

        Running(Player player, String label, int totalTicks, boolean moveCancels, Runnable onComplete, Runnable onCancelled) {
            this.player = player;
            this.label = label;
            this.totalTicks = totalTicks;
            this.moveCancels = moveCancels;
            this.onComplete = onComplete;
            this.onCancelled = onCancelled;
            this.start = player.getLocation();
            this.bar = BossBar.bossBar(title(totalTicks), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
            showCountdown(totalTicks);
            this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0, PERIOD_TICKS);
        }

        private void tick() {
            if (!player.isOnline()) {
                cancel(player, null);
                return;
            }
            if (moveCancels && moved()) {
                cancel(player, "teleport.cancelled-move");
                return;
            }

            elapsed += PERIOD_TICKS;
            double progress = Math.min(1, (double) elapsed / totalTicks);
            bar.progress((float) (1 - progress));
            bar.name(title(totalTicks - elapsed));
            TeleportEffects.warmup(player, elapsed, progress);
            if (elapsed % 20 == 0) {
                TeleportEffects.countdown(player, progress);
                showCountdown(totalTicks - elapsed);
            }

            if (elapsed >= totalTicks) {
                running.remove(player.getUniqueId());
                stop();
                onComplete.run();
            }
        }

        /** Le compte à rebours au milieu de l'écran (« 3 », « 2 », « 1 »), avec « ne bouge pas » si bouger annule. */
        private void showCountdown(int ticksLeft) {
            if (ticksLeft <= 0) {
                return;
            }
            Component main = messages.get(player, "teleport.countdown-title", "time", String.valueOf((ticksLeft + 19) / 20));
            Component sub = messages.get(player, moveCancels ? "teleport.countdown-still" : "teleport.countdown-free",
                    "destination", label);
            player.showTitle(Title.title(main, sub, Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200))));
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
            player.clearTitle();
        }
    }
}
