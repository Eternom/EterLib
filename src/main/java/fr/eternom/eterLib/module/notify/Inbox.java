package fr.eternom.eterLib.module.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Boîte de notifications du réseau : chaque NetworkBus#notify (paiement reçu, demande d'ami, parrainage, pass...) y est
 * gardé, que le joueur soit connecté ou non (les 20 dernières, 30 jours, Redis « inbox:&lt;uuid&gt; »). /inbox les relit ;
 * à la connexion, celles arrivées depuis sa dernière lecture sont annoncées. Le texte est gardé déjà mis en forme
 * (langue par défaut du réseau) : il se relit sur n'importe quel serveur, même sans le plugin qui l'a envoyé.
 */
public final class Inbox implements TabExecutor, Listener {

    private static final String KEY = "inbox:";
    private static final String SEEN = "inbox-seen:";
    private static final String LEFT = "inbox-left:";
    private static final int KEEP = 20;
    private static final Duration TTL = Duration.ofDays(30);
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static volatile Inbox instance;

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final Messages messages;

    public Inbox(JavaPlugin plugin, RedisCache redis, Messages messages) {
        this.plugin = plugin;
        this.redis = redis;
        this.messages = messages;
        instance = this;
    }

    /** Garde une notification déjà mise en forme pour ce joueur (en tâche de fond). Sans boîte (EterLib absent) : rien. */
    public static void record(UUID player, Component text) {
        Inbox inbox = instance;
        if (inbox == null || text == null) {
            return;
        }
        String serialized = MINI.serialize(text);
        long at = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(inbox.plugin, () -> {
            try {
                JsonArray entries = inbox.read(player);
                JsonObject entry = new JsonObject();
                entry.addProperty("at", at);
                entry.addProperty("text", serialized);
                JsonArray kept = new JsonArray();
                kept.add(entry);
                for (int i = 0; i < entries.size() && kept.size() < KEEP; i++) {
                    kept.add(entries.get(i));
                }
                inbox.redis.set(KEY + player, kept.toString(), TTL);
            } catch (RuntimeException ignored) {
                // Redis indisponible : la notification a quand même été montrée si le joueur était là
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonArray entries;
            try {
                entries = read(uuid);
                redis.set(SEEN + uuid, String.valueOf(System.currentTimeMillis()), TTL);
            } catch (RuntimeException e) {
                Bukkit.getScheduler().runTask(plugin, () -> messages.send(player, "error.generic"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> show(player, entries));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }

    private void show(Player player, JsonArray entries) {
        if (entries.isEmpty()) {
            messages.send(player, "inbox.empty");
            return;
        }
        messages.send(player, "inbox.title", "count", String.valueOf(entries.size()));
        long now = System.currentTimeMillis();
        List<JsonObject> list = new ArrayList<>();
        entries.forEach(element -> list.add(element.getAsJsonObject()));
        for (int i = list.size() - 1; i >= 0; i--) { // du plus ancien au plus récent : le dernier reste en bas du chat
            JsonObject entry = list.get(i);
            long seconds = Math.max(0, (now - entry.get("at").getAsLong()) / 1000);
            player.sendMessage(messages.get(player, "inbox.line", Placeholder.component("text", MINI.deserialize(entry.get("text").getAsString())),
                    "ago", EterLib.get().formatDuration(player, Math.max(60, seconds))));
        }
    }

    /** Connexion : les notifications arrivées depuis la dernière lecture de /inbox. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Seulement celles arrivées pendant son absence (les autres, il les a vues en direct) et pas encore relues
                long seen = Math.max(redis.get(SEEN + uuid).map(Long::parseLong).orElse(0L),
                        redis.get(LEFT + uuid).map(Long::parseLong).orElse(Long.MAX_VALUE));
                JsonArray entries = read(uuid);
                int unread = 0;
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).getAsJsonObject().get("at").getAsLong() > seen) {
                        unread++;
                    }
                }
                if (unread == 0) {
                    return;
                }
                int count = unread;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        messages.send(player, "inbox.unread", "count", String.valueOf(count));
                    }
                }, 20 * 6);
            } catch (RuntimeException ignored) {
                // Redis indisponible : pas d'annonce
            }
        });
    }

    /** Départ : l'heure, pour n'annoncer à la prochaine arrivée que ce qui est arrivé entre-temps. */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                redis.set(LEFT + uuid, String.valueOf(now), TTL);
            } catch (RuntimeException ignored) {
                // Redis indisponible
            }
        });
    }

    private JsonArray read(UUID player) {
        return redis.get(KEY + player).map(json -> JsonParser.parseString(json).getAsJsonArray()).orElseGet(JsonArray::new);
    }
}
