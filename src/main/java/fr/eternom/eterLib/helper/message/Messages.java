package fr.eternom.eterLib.helper.message;

import fr.eternom.eterLib.core.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Messages traduits en MiniMessage, dans la langue du client du joueur.
 *
 * - Couleurs de la palette utilisables comme balises : <success>, <error>, <accent>... (language.colors)
 * - Variables : paires nom/valeur, ex : send(player, "home.set", "home", "base") remplit <home>.
 *   Les valeurs sont insérées telles quelles, jamais interprétées comme du MiniMessage.
 * - send() ajoute le préfixe commun à tous les plugins (language.prefix d'EterLib), get() non (titres, items, bossbar...).
 */
public class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String GENERIC_ERROR = "error.generic";

    private final Lang lang;
    private final TagResolver colors;
    private final Component prefix;

    public Messages(Lang lang) {
        this.lang = lang;
        List<TagResolver> resolvers = new ArrayList<>();
        for (Map.Entry<String, String> color : lang.getColors().entrySet()) {
            TextColor value = parseColor(color.getValue());
            if (value != null) {
                resolvers.add(TagResolver.resolver(color.getKey(), Tag.styling(value)));
            }
        }
        this.colors = TagResolver.resolver(resolvers);
        this.prefix = MINI_MESSAGE.deserialize(lang.getPrefix(), colors);
    }

    /** Clés déjà signalées dans la console (une fois chacune). */
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Une clé absente partout : affichée en rouge, et signalée une fois dans la console pour être ajoutée. */
    private Component missing(String key) {
        if (REPORTED.add(lang.owner() + "/" + key)) {
            org.bukkit.Bukkit.getLogger().warning("[" + lang.owner() + "] Texte absent de lang/ : " + key);
        }
        return Component.text(key, NamedTextColor.RED);
    }

    /** Préfixe commun des messages (ex : « Core »), pour composer une ligne à la main. */
    public Component prefix() {
        return prefix;
    }

    public Component get(CommandSender receiver, String key, String... placeholders) {
        String raw = lang.get(localeOf(receiver), key);
        if (raw == null) {
            return missing(key); // clé manquante : visible pour être corrigée
        }
        return MINI_MESSAGE.deserialize(raw, colors, placeholders(withRef(receiver, key, placeholders)));
    }

    /**
     * Comme get, avec des balises en plus : des valeurs déjà mises en forme, ex : le nom d'un objet traduit par le
     * client, Placeholder.component("item", Component.translatable(material.translationKey())).
     */
    public Component get(CommandSender receiver, String key, TagResolver tags, String... placeholders) {
        String raw = lang.get(localeOf(receiver), key);
        if (raw == null) {
            return missing(key);
        }
        return MINI_MESSAGE.deserialize(raw, colors, tags, placeholders(withRef(receiver, key, placeholders)));
    }

    /** error.generic : ajoute <ref>, la référence que le joueur peut donner au staff (ErrorRefs). */
    private static String[] withRef(CommandSender receiver, String key, String[] placeholders) {
        if (!GENERIC_ERROR.equals(key)) {
            return placeholders;
        }
        String[] out = java.util.Arrays.copyOf(placeholders, placeholders.length + 2);
        out[placeholders.length] = "ref";
        out[placeholders.length + 1] = fr.eternom.eterLib.helper.task.ErrorRefs.forReceiver(receiver);
        return out;
    }

    /** Texte sans mise en forme, ex : pour comparer avec ce que le joueur a écrit dans le chat. */
    public String plain(CommandSender receiver, String key, String... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(get(receiver, key, placeholders));
    }

    public void send(CommandSender receiver, String key, String... placeholders) {
        receiver.sendMessage(prefix.append(get(receiver, key, placeholders)));
    }

    public void actionBar(Player player, String key, String... placeholders) {
        player.sendActionBar(get(player, key, placeholders));
    }

    /**
     * Texte brut (MiniMessage, non interprété) de key dans la langue du destinataire, null si absent.
     * Pour le retravailler avant affichage (PlaceholderAPI, découpage en lignes...), puis {@link #render}.
     */
    public String raw(CommandSender receiver, String key) {
        return lang.get(localeOf(receiver), key);
    }

    /**
     * Interprète un texte MiniMessage avec la palette et des variables.
     * @param tags balises en plus (ex : animations, valeurs déjà mises en forme)
     */
    public Component render(String raw, TagResolver tags, String... placeholders) {
        return MINI_MESSAGE.deserialize(raw, colors, tags, placeholders(placeholders));
    }

    private String localeOf(CommandSender receiver) {
        return receiver instanceof Player player
                ? fr.eternom.eterLib.module.language.LanguageChoice.of(player.getUniqueId())
                        .orElseGet(() -> player.locale().toString().toLowerCase(Locale.ROOT))
                : lang.getDefaultLocale();
    }

    private TagResolver placeholders(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Les variables vont par paires nom/valeur");
        }
        TagResolver.Builder builder = TagResolver.builder();
        for (int i = 0; i < pairs.length; i += 2) {
            builder.resolver(Placeholder.unparsed(pairs[i], pairs[i + 1]));
        }
        return builder.build();
    }

    private static TextColor parseColor(String value) {
        return value.startsWith("#") ? TextColor.fromHexString(value) : NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
    }
}
