package fr.eternom.eterLib.module.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Une ligne d'action, telle qu'on l'écrit dans un PNJ, un menu ou une commande (logique pure, testée) :
 * <pre>
 *     eterjob:quests mineur                 lance l'action « eterjob:quests » avec l'argument « mineur »
 *     eterlib:message "Bonjour {player} !"  un argument entre guillemets peut contenir des espaces
 *     ?eterjob:has-job mineur               condition : si elle est fausse, la suite de la chaîne n'est pas lancée
 *     ?!eterlib:permission eter.vip         condition inversée
 *     &#64;survie eterjob:guild                 sur ce serveur précis (le joueur y est envoyé s'il est ailleurs)
 * </pre>
 *
 * @param server    serveur imposé (sans le @), null : ici, ou un serveur qui fournit l'action
 * @param condition ligne de condition (« ? »)
 * @param negate    condition inversée (« ?! »)
 * @param id        identifiant « plugin:action », en minuscules
 */
public record ActionLine(String server, boolean condition, boolean negate, String id, List<String> args) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+:[a-z0-9_.-]+");

    public ActionLine {
        args = List.copyOf(args);
    }

    /** Lit une ligne ; IllegalArgumentException si elle est vide ou si l'identifiant n'est pas « plugin:action ». */
    public static ActionLine parse(String text) {
        List<String> words = split(text == null ? "" : text.trim());
        if (words.isEmpty()) {
            throw new IllegalArgumentException("ligne d'action vide");
        }
        String server = null;
        if (words.getFirst().startsWith("@")) {
            server = words.removeFirst().substring(1);
            if (server.isEmpty() || words.isEmpty()) {
                throw new IllegalArgumentException("serveur ou action manquant : " + text);
            }
        }
        String head = words.removeFirst();
        boolean condition = head.startsWith("?");
        boolean negate = head.startsWith("?!");
        String id = head.substring(negate ? 2 : condition ? 1 : 0).toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("action invalide (plugin:action attendu) : " + head);
        }
        return new ActionLine(server, condition, negate, id, words);
    }

    /** Le plugin de l'action (« eterjob » pour « eterjob:quests »). */
    public String namespace() {
        return id.substring(0, id.indexOf(':'));
    }

    /** Les arguments avec {player}, {uuid}... remplacés. */
    public List<String> resolve(Map<String, String> values) {
        List<String> resolved = new ArrayList<>(args.size());
        for (String arg : args) {
            String value = arg;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                value = value.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            resolved.add(value);
        }
        return resolved;
    }

    /** La ligne réécrite (pour l'envoyer à un autre serveur ou l'afficher). */
    public String format() {
        StringBuilder builder = new StringBuilder();
        if (server != null) {
            builder.append('@').append(server).append(' ');
        }
        builder.append(condition ? (negate ? "?!" : "?") : "").append(id);
        for (String arg : args) {
            builder.append(' ').append(arg.isEmpty() || arg.contains(" ") || arg.contains("\"")
                    ? '"' + arg.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : arg);
        }
        return builder.toString();
    }

    /** Découpe en mots ; « "..." » garde les espaces, « \" » un guillemet dans un argument entre guillemets. */
    static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < text.length()) {
                    current.append(text.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    words.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("guillemet non fermé : " + text);
        }
        if (started) {
            words.add(current.toString());
        }
        return words;
    }
}
