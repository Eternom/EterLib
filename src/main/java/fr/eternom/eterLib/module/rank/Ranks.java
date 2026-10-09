package fr.eternom.eterLib.module.rank;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.tag.PlayerTags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Le grade affiché d'un joueur, le même pour tous les plugins (chat, pseudo au-dessus de la tête, sidebar...) :
 * préfixe, suffixe, poids et groupe de LuckPerms, et le BADGE (étiquette « badge » posée par un plugin, ex : le tag du
 * clan) qui remplace le préfixe. Sans LuckPerms : grade vide, le plugin fonctionne quand même. Thread principal.
 */
public class Ranks {

    /** Étiquette qui remplace le préfixe du grade (posée par EterClan : tag du clan, sauf pour le staff). */
    public static final String BADGE = "badge";

    /** record : deux grades identiques sont égaux, ce qui permet de ne redessiner qu'en cas de changement. */
    public record Rank(Component prefix, Component suffix, int weight, String group) {

        public static final Rank NONE = new Rank(Component.empty(), Component.empty(), 0, "default");
    }

    private final PlayerTags tags;
    private final Messages messages;

    public Ranks(PlayerTags tags, Messages messages) {
        this.tags = tags;
        this.messages = messages;
    }

    /** Le grade tel qu'on l'affiche : celui de LuckPerms, le préfixe remplacé par le badge s'il y en a un. */
    public Rank of(Player player) {
        Rank rank = luckPerms(player);
        String badge = tags.get(player, BADGE);
        return badge.isBlank() ? rank : new Rank(messages.render(badge, TagResolver.empty()), rank.suffix(), rank.weight(), rank.group());
    }

    /** Le grade LuckPerms seul, sans badge. */
    public Rank luckPerms(Player player) {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getPluginManager().getPlugin("LuckPerms") == null ? null
                : Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            return Rank.NONE;
        }
        LuckPerms luckPerms = provider.getProvider();
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return Rank.NONE;
        }
        CachedMetaData meta = user.getCachedData().getMetaData();
        Group group = luckPerms.getGroupManager().getGroup(user.getPrimaryGroup());
        return new Rank(spaced(parse(meta.getPrefix()), true), spaced(parse(meta.getSuffix()), false),
                group == null ? 0 : group.getWeight().orElse(0), user.getPrimaryGroup());
    }

    /** Une espace entre le grade et le pseudo, si le préfixe (ou le suffixe) LuckPerms n'en a pas. */
    private static Component spaced(Component part, boolean prefix) {
        String plain = PlainTextComponentSerializer.plainText().serialize(part);
        if (plain.isBlank()) {
            return Component.empty();
        }
        if (prefix) {
            return plain.endsWith(" ") ? part : part.append(Component.space());
        }
        return plain.startsWith(" ") ? part : Component.space().append(part);
    }

    /** Les préfixes LuckPerms sont souvent en codes « & » ; sinon on les lit comme du MiniMessage. */
    private static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        if (text.indexOf('&') >= 0 || text.indexOf('§') >= 0) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(text.replace('§', '&'));
        }
        return MiniMessage.miniMessage().deserialize(text);
    }
}
