package fr.eternom.eterLib.module.world;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Règles de jeu de config.yml > game-rules (noms de Minecraft 26, ceux de /gamerule : locator_bar...), appliquées à
 * chaque monde de ce serveur : au démarrage, puis à chaque monde chargé. Les lobbys créés par l'orchestrateur les ont
 * donc d'office. Section absente (ancienne config) : locator_bar false. Thread principal.
 */
public class WorldRules implements Listener {

    /** Quand config.yml n'a pas de section game-rules. */
    private static final Map<String, Object> DEFAULTS = Map.of("locator_bar", false);

    private final Map<GameRule<?>, Object> rules = new LinkedHashMap<>();

    /** @param section config.yml > game-rules, null si absente (les règles par défaut s'appliquent) */
    public WorldRules(ConfigurationSection section, Logger logger) {
        Map<String, Object> values = section == null ? DEFAULTS : section.getValues(false);
        values.forEach((name, value) -> {
            GameRule<?> rule = Registry.GAME_RULE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            Object parsed = rule == null ? null : parse(rule.getType(), value);
            if (parsed == null) {
                logger.warning("game-rules." + name + " : règle inconnue ou valeur invalide (« " + value + " »), ignorée");
            } else {
                rules.put(rule, parsed);
            }
        });
    }

    public void applyAll() {
        Bukkit.getWorlds().forEach(this::apply);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        apply(event.getWorld());
    }

    private void apply(World world) {
        rules.forEach((rule, value) -> set(world, rule, value));
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(World world, GameRule<T> rule, Object value) {
        world.setGameRule(rule, (T) value);
    }

    /** true/false pour une règle oui/non, un nombre entier pour une règle chiffrée ; null si la valeur ne convient pas. */
    private static Object parse(Class<?> type, Object value) {
        String text = String.valueOf(value).trim();
        if (type == Boolean.class) {
            return text.equalsIgnoreCase("true") ? Boolean.TRUE : text.equalsIgnoreCase("false") ? Boolean.FALSE : null;
        }
        if (type == Integer.class) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
