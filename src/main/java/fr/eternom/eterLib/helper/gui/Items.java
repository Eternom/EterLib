package fr.eternom.eterLib.helper.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class Items {

    private Items() {
    }

    public static ItemStack item(Material material, Component name, List<Component> lore) {
        return item(material, name, lore, false);
    }

    /** glint : effet brillant d'enchantement, pour faire ressortir l'item. */
    public static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = ItemStack.of(material);
        // Minecraft met les noms et descriptions d'items en italique par défaut
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return item;
    }

    /** Vitre de décoration : aucune infobulle au survol. */
    public static ItemStack pane(Material material) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> meta.setHideTooltip(true));
        return item;
    }

    public static ItemStack head(PlayerProfile profile, Component name, List<Component> lore) {
        ItemStack item = item(Material.PLAYER_HEAD, name, lore);
        item.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(profile));
        return item;
    }
}
