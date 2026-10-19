package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.EterLib;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Particules et sons de téléportation, visibles et audibles par les joueurs autour ; pour un joueur invisible (staff),
 * par lui seul : sinon le bruit et les particules trahiraient sa présence.
 */
final class TeleportEffects {

    private TeleportEffects() {
    }

    /** Pendant l'attente : deux spirales qui montent autour du joueur. progress va de 0 à 1. */
    static void warmup(Player player, int tick, double progress) {
        Location center = player.getLocation();
        double angle = tick * 0.35;
        double height = progress * 2;
        for (int i = 0; i < 2; i++) {
            double a = angle + i * Math.PI;
            Location point = center.clone().add(Math.cos(a) * 0.8, height, Math.sin(a) * 0.8);
            particle(player, Particle.END_ROD, point, 1, 0, 0, 0, 0);
        }
        particle(player, Particle.PORTAL, center.clone().add(0, 0.1, 0), 4, 0.4, 0, 0.4, 0.1);
    }

    /** Une note par seconde, de plus en plus aiguë. */
    static void countdown(Player player, double progress) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, (float) (0.8 + progress));
    }

    /** Au départ ou à l'arrivée de player, à location. */
    static void burst(Player player, Location location) {
        particle(player, Particle.REVERSE_PORTAL, location.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.05);
        if (hidden(player)) {
            player.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        } else {
            location.getWorld().playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        }
    }

    private static void particle(Player player, Particle particle, Location at, int count, double dx, double dy, double dz,
                                 double speed) {
        if (hidden(player)) {
            player.spawnParticle(particle, at, count, dx, dy, dz, speed);
        } else {
            at.getWorld().spawnParticle(particle, at, count, dx, dy, dz, speed);
        }
    }

    private static boolean hidden(Player player) {
        return EterLib.get().getVanish().isVanished(player.getUniqueId());
    }

    static void cancelled(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
    }
}
