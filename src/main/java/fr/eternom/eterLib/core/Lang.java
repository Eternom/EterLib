package fr.eternom.eterLib.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fichiers de langue d'UN plugin : plugins/<plugin>/lang/<locale>.yml (codes Minecraft : en_us, fr_fr...).
 * Les fichiers fournis dans le jar du plugin sont copiés au premier démarrage et servent de valeurs par défaut :
 * une clé ajoutée par une mise à jour existe donc même dans un ancien fichier modifié.
 * On peut ajouter une langue en déposant simplement un nouveau fichier dans le dossier.
 * La langue par défaut et la palette sont communes à tous les plugins Eter (config d'EterLib).
 */
public class Lang {

    private final JavaPlugin plugin;
    private final String defaultLocale;
    private final Map<String, String> colors;
    private final String[] bundled;
    private final Map<String, YamlConfiguration> languages = new HashMap<>();

    /** @param bundled langues fournies dans le jar du plugin (lang/<locale>.yml), ex : "en_us", "fr_fr" */
    public Lang(JavaPlugin plugin, String defaultLocale, Map<String, String> colors, String... bundled) {
        this.plugin = plugin;
        this.defaultLocale = defaultLocale.toLowerCase(Locale.ROOT);
        this.colors = colors;
        this.bundled = bundled;
    }

    public void load() {
        File folder = new File(plugin.getDataFolder(), "lang");
        for (String locale : bundled) {
            if (!new File(folder, locale + ".yml").exists()) {
                plugin.saveResource("lang/" + locale + ".yml", false);
            }
        }

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        for (File file : files == null ? new File[0] : files) {
            String locale = file.getName().replace(".yml", "").toLowerCase(Locale.ROOT);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            InputStream defaults = plugin.getResource("lang/" + locale + ".yml");
            if (defaults != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
            languages.put(locale, yaml);
        }

        if (!languages.containsKey(defaultLocale)) {
            throw new IllegalStateException("Langue par défaut introuvable : " + plugin.getName() + "/lang/" + defaultLocale + ".yml");
        }
        plugin.getLogger().info("Langues : " + String.join(", ", languages.keySet()) + " (défaut : " + defaultLocale + ")");
    }

    /**
     * Texte brut (MiniMessage) de key pour locale : langue exacte, sinon même langue d'une autre région
     * (fr_ca -> fr_fr), sinon langue par défaut. null si la clé n'existe nulle part.
     */
    public String get(String locale, String key) {
        String value = find(languages.get(locale), key);
        if (value == null) {
            String language = locale.split("_")[0] + "_";
            value = languages.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(language))
                    .map(entry -> find(entry.getValue(), key))
                    .filter(found -> found != null)
                    .findFirst()
                    .orElse(null);
        }
        return value != null ? value : find(languages.get(defaultLocale), key);
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    /** Couleurs nommées de la palette commune : nom -> couleur ("#FF7A00" ou "gray"). */
    public Map<String, String> getColors() {
        return colors;
    }

    private String find(YamlConfiguration yaml, String key) {
        return yaml == null ? null : yaml.getString(key);
    }
}
