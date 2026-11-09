package fr.eternom.eterLib.module.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * Une arrivée sûre près d'une position : les pieds et la tête dans un bloc où l'on respire et ne brûle pas, posés sur un
 * bloc solide qui n'est ni lave ni feu ni cactus ni magma. Cherché autour (rayon 3) puis plus haut ; rien de sûr : la
 * position d'origine (mieux vaut arriver que rester bloqué). Pour aller vers un joueur, qui peut être dans la lave, en
 * l'air ou en train de creuser.
 */
public final class SafeLocation {

    private static final int RADIUS = 3;
    private static final int UP = 6;

    private SafeLocation() {
    }

    public static Location find(Location wanted) {
        if (wanted.getWorld() == null || isSafe(wanted.getBlock())) {
            return wanted;
        }
        Block origin = wanted.getBlock();
        for (int dy = 0; dy <= UP; dy++) {
            for (int radius = 0; radius <= RADIUS; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        for (int sign : dy == 0 ? new int[]{1} : new int[]{1, -1}) {
                            Block feet = origin.getRelative(dx, dy * sign, dz);
                            if (isSafe(feet)) {
                                Location safe = feet.getLocation().add(0.5, 0, 0.5);
                                safe.setYaw(wanted.getYaw());
                                safe.setPitch(wanted.getPitch());
                                return safe;
                            }
                        }
                    }
                }
            }
        }
        return wanted;
    }

    /** Les pieds dans feet : pieds et tête libres et sans danger, un sol solide sans danger dessous. */
    static boolean isSafe(Block feet) {
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        if (feet.getY() <= feet.getWorld().getMinHeight() || head.getY() >= feet.getWorld().getMaxHeight()) {
            return false;
        }
        return passable(feet) && passable(head) && ground.getType().isSolid() && !dangerous(ground.getType());
    }

    private static boolean passable(Block block) {
        Material type = block.getType();
        return block.isPassable() && !block.isLiquid() && !dangerous(type);
    }

    private static boolean dangerous(Material type) {
        return switch (type) {
            case LAVA, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, MAGMA_BLOCK, SWEET_BERRY_BUSH, WITHER_ROSE,
                 POWDER_SNOW, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }
}
