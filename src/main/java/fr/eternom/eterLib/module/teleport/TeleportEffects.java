package fr.eternom.eterLib.module.teleport;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Particules et sons de téléportation, visibles et audibles par les joueurs autour. */
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
            center.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
        }
        center.getWorld().spawnParticle(Particle.PORTAL, center.clone().add(0, 0.1, 0), 4, 0.4, 0, 0.4, 0.1);
    }

    /** Une note par seconde, de plus en plus aiguë. */
    static void countdown(Player player, double progress) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, (float) (0.8 + progress));
    }

    static void burst(Location location) {
        location.getWorld().spawnParticle(Particle.REVERSE_PORTAL, location.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.05);
        location.getWorld().playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
    }

    static void cancelled(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
    }
}
