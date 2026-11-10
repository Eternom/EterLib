package fr.eternom.eterLib.module.quest;

import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Le pont vers le moteur de quêtes (EterQuest), sans en dépendre : un plugin dit ce que le joueur fait (signal), demande
 * si une quête en veut encore (wanted), ou passe un clic sur un PNJ (interact). Sans moteur de quêtes sur ce serveur,
 * rien ne se passe (0, false). Thread principal.
 *
 * <pre>
 *   QuestBridge.signal(player, "eterhub:harvest", Map.of("crop", "wheat", "quality", "golden"), 1);
 *   if (QuestBridge.wanted(player, "eterhub:harvest", Map.of("crop", "wheat")) > 0) { ... }
 * </pre>
 * Les clés et valeurs des données sont en minuscules. wanted est souple : une donnée absente (la qualité, pas encore
 * tirée) n'empêche pas un objectif de correspondre.
 */
public final class QuestBridge {

    /** Le moteur de quêtes (EterQuest). */
    public interface Provider {

        /** Le joueur a fait amount fois cette chose : les objectifs de ses quêtes qui l'attendent avancent. */
        void signal(Player player, String key, Map<String, String> data, int amount);

        /** Combien ses quêtes en cours en veulent encore (somme de ce qui reste), 0 sinon. */
        int wanted(Player player, String key, Map<String, String> data);

        /** Un clic sur ce PNJ (« eternpc:fermier ») : true si une quête s'en est servie (le PNJ ne fait alors rien d'autre). */
        boolean interact(Player player, String npc);
    }

    private static volatile Provider provider;

    private QuestBridge() {
    }

    public static void register(Provider value) {
        provider = value;
    }

    public static void unregister(Provider value) {
        if (provider == value) {
            provider = null;
        }
    }

    public static boolean available() {
        return provider != null;
    }

    public static void signal(Player player, String key, Map<String, String> data, int amount) {
        Provider current = provider;
        if (current != null && amount > 0) {
            current.signal(player, key, data, amount);
        }
    }

    public static int wanted(Player player, String key, Map<String, String> data) {
        Provider current = provider;
        return current == null ? 0 : current.wanted(player, key, data);
    }

    public static boolean interact(Player player, String npc) {
        Provider current = provider;
        return current != null && current.interact(player, npc);
    }
}
