package fr.eternom.eterLib.module.world;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Blocs « naturels » ou posés par un joueur. Les succès, défis et bonus qui comptent les blocs cassés ne doivent compter
 * que les naturels : sinon, poser et recasser le même minerai (ou la même bûche) ferait monter le compteur sans fin.
 *
 * Seules les matières qui comptent quelque part sont suivies (minerais, bûches, pierres, terre et sable, melons...).
 * Chaque bloc posé est noté dans les données du chunk (sauvegardées avec le monde) ; la note suit les pistons et
 * disparaît quand le bloc est cassé, brûle ou explose. À lire AVANT MONITOR dans BlockBreakEvent (la note est retirée
 * à MONITOR) : {@code lib.getPlacedBlocks().isPlaced(block)}.
 */
public class PlacedBlocks implements Listener {

    private static final Set<Material> TRACKED = EnumSet.noneOf(Material.class);

    static {
        for (Tag<Material> tag : List.of(Tag.COAL_ORES, Tag.IRON_ORES, Tag.COPPER_ORES, Tag.GOLD_ORES, Tag.REDSTONE_ORES,
                Tag.LAPIS_ORES, Tag.DIAMOND_ORES, Tag.EMERALD_ORES, Tag.LOGS, Tag.SAND)) {
            TRACKED.addAll(tag.getValues());
        }
        TRACKED.addAll(List.of(Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE, Material.ANCIENT_DEBRIS,
                Material.STONE, Material.DEEPSLATE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
                Material.CALCITE, Material.NETHERRACK, Material.END_STONE, Material.BLACKSTONE, Material.BASALT,
                Material.GRAVEL, Material.CLAY, Material.DIRT, Material.GRASS_BLOCK, Material.MELON, Material.PUMPKIN,
                Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.AMETHYST_CLUSTER, Material.OBSIDIAN));
    }

    private final NamespacedKey key;

    public PlacedBlocks(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "placed");
    }

    /** true si ce bloc a été posé par un joueur (et n'a pas été cassé depuis). */
    public boolean isPlaced(Block block) {
        if (!TRACKED.contains(block.getType())) {
            return false;
        }
        int[] placed = read(block.getChunk());
        int packed = pack(block);
        for (int value : placed) {
            if (value == packed) {
                return true;
            }
        }
        return false;
    }

    /** Matières suivies : les autres ne sont jamais « posées » pour isPlaced. */
    public static boolean isTracked(Material material) {
        return TRACKED.contains(material);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (TRACKED.contains(event.getBlockPlaced().getType())) {
            add(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        remove(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        remove(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::remove);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::remove);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    /** Un bloc posé poussé par un piston reste « posé » à sa nouvelle place (sinon : pousser, puis casser). */
    private void move(List<Block> blocks, BlockFace direction) {
        List<Block> moved = new ArrayList<>();
        for (Block block : blocks) {
            if (isPlaced(block)) {
                moved.add(block);
            }
        }
        moved.forEach(this::remove);
        moved.forEach(block -> add(block.getRelative(direction)));
    }

    private void add(Block block) {
        Chunk chunk = block.getChunk();
        int[] placed = read(chunk);
        int packed = pack(block);
        for (int value : placed) {
            if (value == packed) {
                return;
            }
        }
        int[] more = Arrays.copyOf(placed, placed.length + 1);
        more[placed.length] = packed;
        chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY, more);
    }

    private void remove(Block block) {
        Chunk chunk = block.getChunk();
        int[] placed = read(chunk);
        if (placed.length == 0) {
            return;
        }
        int packed = pack(block);
        int[] kept = Arrays.stream(placed).filter(value -> value != packed).toArray();
        if (kept.length == placed.length) {
            return;
        }
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (kept.length == 0) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.INTEGER_ARRAY, kept);
        }
    }

    private int[] read(Chunk chunk) {
        int[] placed = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        return placed == null ? new int[0] : placed;
    }

    /** Position dans le chunk : y (décalé, toujours positif) puis x et z sur 4 bits chacun. */
    private static int pack(Block block) {
        return ((block.getY() + 2048) << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }
}
