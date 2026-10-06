package fr.eternom.eterLib.module.teleport;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportListener implements Listener {

    private final JavaPlugin plugin;
    private final TeleportService teleports;
    private final TeleportWarmup warmup;
    private final boolean cancelOnDamage;
    /** Joueurs arrivés d'un autre serveur par téléportation : effet d'arrivée une fois connectés. */
    private final Set<UUID> arrivals = ConcurrentHashMap.newKeySet();

    public TeleportListener(JavaPlugin plugin, TeleportService teleports, TeleportWarmup warmup, boolean cancelOnDamage) {
        this.plugin = plugin;
        this.teleports = teleports;
        this.warmup = warmup;
        this.cancelOnDamage = cancelOnDamage;
    }

    /**
     * Arrivée d'un joueur envoyé par un autre serveur (home, tpa...).
     * L'événement est asynchrone et le joueur attend qu'il se termine : on peut lire la base
     * et le faire apparaître directement au home, sans téléportation visible.
     */
    @EventHandler
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID player = event.getConnection().getProfile().getId();
        if (player == null) {
            return;
        }
        teleports.takePendingLocation(player).ifPresent(location -> {
            event.setSpawnLocation(location);
            arrivals.add(player);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (arrivals.remove(player.getUniqueId())) {
            // Quelques ticks : laisser le client charger le monde pour qu'il voie l'effet
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    TeleportEffects.burst(player.getLocation());
                }
            }, 10);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (cancelOnDamage && event.getEntity() instanceof Player player) {
            warmup.cancel(player, "teleport.cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        warmup.cancel(event.getPlayer(), null);
    }
}
