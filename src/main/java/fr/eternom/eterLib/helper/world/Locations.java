package fr.eternom.eterLib.helper.world;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Distances sans piège : {@link Location#distanceSquared} lève une exception entre deux mondes (un joueur téléporté,
 * un portail, une liste relue toutes les quelques ticks...). Ici, deux points de mondes différents ne sont jamais
 * proches, et un point sans monde non plus.
 */
public final class Locations {

    private Locations() {
    }

    /** Les deux points sont dans le même monde (et en ont un). */
    public static boolean sameWorld(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    /** a et b sont dans le même monde, à range blocs ou moins l'un de l'autre. */
    public static boolean within(Location a, Location b, double range) {
        return sameWorld(a, b) && a.distanceSquared(b) <= range * range;
    }

    /** Les deux entités sont dans le même monde, à range blocs ou moins. */
    public static boolean within(Entity a, Entity b, double range) {
        return a != null && b != null && within(a.getLocation(), b.getLocation(), range);
    }

    /** Distance au carré, ou {@link Double#POSITIVE_INFINITY} entre deux mondes (pour trier : le plus loin possible). */
    public static double distanceSquared(Location a, Location b) {
        return sameWorld(a, b) ? a.distanceSquared(b) : Double.POSITIVE_INFINITY;
    }
}
