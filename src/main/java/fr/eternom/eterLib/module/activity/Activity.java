package fr.eternom.eterLib.module.activity;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Activité des joueurs de ce serveur, la même pour tous les plugins Eter (temps de jeu actif, XP du pass, sommeil...).
 * Deux mesures :
 * <ul>
 *     <li>actif : a marché (changé de bloc), tourné la tête, agi (clic) ou parlé ;</li>
 *     <li>regard : a tourné la tête ou parlé. Plus strict : un courant d'eau ou un wagonnet en boucle déplace le joueur,
 *     mais ne le fait pas regarder autour de lui (classements et statistiques sans plafond).</li>
 * </ul>
 * AFK : aucune activité depuis afkAfter (ni depuis l'arrivée, pour ne pas compter AFK celui qui vient d'arriver).
 * Les petits recalages du serveur (sans changement de bloc ni d'orientation) ne comptent pas.
 */
public class Activity implements Listener {

    private final long afkMillis;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLook = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joined = new ConcurrentHashMap<>();

    public Activity(Duration afkAfter) {
        this.afkMillis = afkAfter.toMillis();
    }

    /** A marché, tourné la tête, agi ou parlé depuis since (millisecondes, System.currentTimeMillis). */
    public boolean activeSince(UUID player, long since) {
        return lastActive.getOrDefault(player, 0L) >= since;
    }

    /** A tourné la tête ou parlé depuis since (millisecondes, System.currentTimeMillis). */
    public boolean lookedSince(UUID player, long since) {
        return lastLook.getOrDefault(player, 0L) >= since;
    }

    /** Rien fait depuis afk-minutes (config.yml > activity), arrivée comprise. */
    public boolean isAfk(Player player) {
        UUID uuid = player.getUniqueId();
        long last = Math.max(lastActive.getOrDefault(uuid, 0L), joined.getOrDefault(uuid, 0L));
        return last < System.currentTimeMillis() - afkMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock() || event.hasChangedOrientation()) {
            lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
        if (event.hasChangedOrientation()) {
            lastLook.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        long now = System.currentTimeMillis();
        lastActive.put(event.getPlayer().getUniqueId(), now);
        lastLook.put(event.getPlayer().getUniqueId(), now);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        joined.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastActive.remove(uuid);
        lastLook.remove(uuid);
        joined.remove(uuid);
    }
}
