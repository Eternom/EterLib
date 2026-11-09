package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.combat.CombatTracker;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arrivées d'un autre serveur, annulation de l'attente par les dégâts, protection à l'arrivée.
 * teleport.cancel-on-damage : « combat » (ou true, par défaut) = seuls les coups d'un joueur ou d'un monstre annulent
 * l'attente (pas une chute, la faim, la noyade, le feu) ; « all » = tout dégât ; « false » = jamais.
 */
public class TeleportListener implements Listener {

    /** Ce qui annule l'attente quand le joueur prend des dégâts. */
    public enum DamageMode { NONE, COMBAT, ALL;

        public static DamageMode parse(String value) {
            return switch (value == null ? "combat" : value.toLowerCase(Locale.ROOT)) {
                case "false", "none" -> NONE;
                case "all" -> ALL;
                default -> COMBAT;
            };
        }
    }

    private final JavaPlugin plugin;
    private final TeleportService teleports;
    private final TeleportWarmup warmup;
    private final DamageMode damageMode;
    private final CombatTracker combat;
    /** Joueurs arrivés d'un autre serveur par téléportation : effet d'arrivée une fois connectés. */
    private final Set<UUID> arrivals = ConcurrentHashMap.newKeySet();

    public TeleportListener(JavaPlugin plugin, TeleportService teleports, TeleportWarmup warmup, DamageMode damageMode,
                            CombatTracker combat) {
        this.plugin = plugin;
        this.teleports = teleports;
        this.warmup = warmup;
        this.damageMode = damageMode;
        this.combat = combat;
    }

    /**
     * Arrivée d'un joueur envoyé par un autre serveur (home, tpa...).
     * L'événement est asynchrone et le joueur attend qu'il se termine : on peut lire la base
     * et le faire apparaître directement au home, sans téléportation visible. Son combat venu d'ailleurs est relu.
     */
    @EventHandler
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID player = event.getConnection().getProfile().getId();
        if (player == null) {
            return;
        }
        combat.load(player);
        teleports.takePendingLocation(player).ifPresent(location -> {
            event.setSpawnLocation(location);
            arrivals.add(player);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (arrivals.remove(player.getUniqueId())) {
            teleports.protect(player.getUniqueId());
            // Quelques ticks : laisser le client charger le monde pour qu'il voie l'effet
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    TeleportEffects.burst(player, player.getLocation());
                }
            }, 10);
        } else {
            UUID uuid = player.getUniqueId();
            Tasks.async(plugin, () -> combat.load(uuid), "Combat non relu");
        }
    }

    /** Protégé à l'arrivée : aucun dégât. Frapper met fin à la protection (pas d'attaque invulnérable). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProtected(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter
                    ? shooter : byEntity.getDamager();
            if (damager instanceof Player attacker) {
                teleports.unprotect(attacker.getUniqueId());
            }
        }
        if (event.getEntity() instanceof Player player && teleports.isProtected(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || damageMode == DamageMode.NONE) {
            return;
        }
        if (damageMode == DamageMode.ALL || event instanceof EntityDamageByEntityEvent) {
            warmup.cancel(player, "teleport.cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        warmup.cancel(event.getPlayer(), null);
        teleports.unprotect(event.getPlayer().getUniqueId());
    }
}
