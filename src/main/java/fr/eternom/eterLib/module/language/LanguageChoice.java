package fr.eternom.eterLib.module.language;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Langue choisie par le joueur (/language fr|en|auto), à la place de celle de son client : gardée dans Redis, la même
 * sur tout le réseau, lue à chaque arrivée. Tous les textes des plugins Eter passent par Messages, qui la consulte.
 * « auto » : de nouveau la langue du client.
 */
public final class LanguageChoice implements Listener, TabExecutor {

    /** Code court -> fichier de langue. */
    private static final Map<String, String> LANGUAGES = Map.of("fr", "fr_fr", "en", "en_us");
    private static final Map<UUID, String> CHOSEN = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final Messages messages;

    public LanguageChoice(JavaPlugin plugin, RedisCache redis, Messages messages) {
        this.plugin = plugin;
        this.redis = redis;
        this.messages = messages;
    }

    /** La langue choisie par ce joueur (ex : fr_fr), vide s'il garde celle de son client. */
    public static Optional<String> of(UUID player) {
        return Optional.ofNullable(CHOSEN.get(player));
    }

    /** Avant l'arrivée (fil à part, bloquant permis) : le choix est déjà là pour les premiers messages. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            redis.get(key(event.getUniqueId())).filter(LANGUAGES::containsValue)
                    .ifPresent(locale -> CHOSEN.put(event.getUniqueId(), locale));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Langue choisie non lue pour " + event.getName() + " : " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        CHOSEN.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (args.length == 0) {
            messages.send(player, "language.current", "language",
                    messages.plain(player, "language.name." + of(player.getUniqueId()).orElse("auto")));
            messages.send(player, "language.choose");
            return true;
        }
        String choice = args[0].toLowerCase(Locale.ROOT);
        UUID uuid = player.getUniqueId();
        if (choice.equals("auto")) {
            CHOSEN.remove(uuid);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> redis.delete(key(uuid)));
        } else if (LANGUAGES.containsKey(choice)) {
            String locale = LANGUAGES.get(choice);
            CHOSEN.put(uuid, locale);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> redis.set(key(uuid), locale));
        } else {
            messages.send(player, "language.choose");
            return true;
        }
        messages.send(player, "language.set", "language",
                messages.plain(player, "language.name." + of(uuid).orElse("auto")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? List.of("fr", "en", "auto").stream().filter(code -> code.startsWith(args[0].toLowerCase(Locale.ROOT))).toList()
                : List.of();
    }

    private static String key(UUID player) {
        return "eterlib:language:" + player;
    }
}
