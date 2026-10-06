package fr.eternom.eterLib.module.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Où envoyer un joueur, sur n'importe quel serveur du réseau : une position fixe (home, spawn...)
 * ou un autre joueur (tpa), dont la position n'est lue qu'au moment de l'arrivée, car il peut bouger.
 *
 * @param label nom affiché dans la bossbar et les messages (nom du home, du joueur...)
 */
public record Destination(String server, String world, double x, double y, double z, float yaw, float pitch,
                          UUID targetPlayer, String label) {

    public static Destination at(String server, String world, double x, double y, double z, float yaw, float pitch,
                                 String label) {
        return new Destination(server, world, x, y, z, yaw, pitch, null, label);
    }

    public static Destination toPlayer(UUID target, String server, String label) {
        return new Destination(server, null, 0, 0, 0, 0, 0, target, label);
    }

    public boolean isOn(String server) {
        return this.server.equals(server);
    }

    /** Position sur CE serveur, ou null si le monde n'existe pas / le joueur visé n'est pas ici. */
    public Location resolve() {
        if (targetPlayer != null) {
            Player target = Bukkit.getPlayer(targetPlayer);
            return target == null ? null : target.getLocation();
        }
        World bukkitWorld = Bukkit.getWorld(world);
        return bukkitWorld == null ? null : new Location(bukkitWorld, x, y, z, yaw, pitch);
    }
}
