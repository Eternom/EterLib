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
 * - send() ajoute le préfixe (clé "prefix"), get() non (titres, items, bossbar...).
 */
public class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Lang lang;
    private final TagResolver colors;

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
    }

    public Component get(CommandSender receiver, String key, String... placeholders) {
        String raw = lang.get(localeOf(receiver), key);
        if (raw == null) {
            return Component.text(key, NamedTextColor.RED); // clé manquante : visible pour être corrigée
        }
        return MINI_MESSAGE.deserialize(raw, colors, placeholders(placeholders));
    }

    /** Texte sans mise en forme, ex : pour comparer avec ce que le joueur a écrit dans le chat. */
    public String plain(CommandSender receiver, String key, String... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(get(receiver, key, placeholders));
    }

    public void send(CommandSender receiver, String key, String... placeholders) {
        receiver.sendMessage(get(receiver, "prefix").append(get(receiver, key, placeholders)));
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
                ? player.locale().toString().toLowerCase(Locale.ROOT)
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
