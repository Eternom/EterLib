package fr.eternom.eterLib.module.reputation;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.action.Actions;
import fr.eternom.eterLib.module.action.Actions.Argument;
import fr.eternom.eterLib.module.tag.PlayerTags;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * La réputation d'un joueur, une seule pour tout le réseau (quêtes faites, aide aux villageois... et plus tard ce qui la
 * fait baisser). Chaque PNJ ne l'entend pas pareil : un ratio par PNJ (EterNpc, 1 par défaut) donne la réputation
 * « auprès de lui » = réputation × ratio. Un PNJ isolé (ratio 0,8) demande donc un peu plus de travail pour le même
 * seuil affiché.
 *
 * Tables eterlib_reputation (uuid, points) et eterlib_reputation_log (60 jours). Gardée en mémoire pour les joueurs
 * connectés ici ; un changement sur un autre serveur arrive par le bus. Étiquette <tag_reputation>.
 * Action eterlib:reputation <points> [raison] ; condition ?eterlib:reputation <minimum> [pnj].
 */
public final class Reputation implements Listener {

    private static final String TABLE = "reputation";
    private static final String LOG = "reputation_log";
    private static final String TAG = "reputation";

    private final JavaPlugin lib;
    private final Database database;
    private final PlayerTags tags;
    private final NetworkBus bus;
    private final Map<UUID, Double> online = new ConcurrentHashMap<>();
    private volatile Function<String, Double> ratios = npc -> 1.0;

    public Reputation(JavaPlugin lib, Database database, PlayerTags tags, NetworkBus bus) {
        this.lib = lib;
        this.database = database;
        this.tags = tags;
        this.bus = bus;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("points", Column.Type.DOUBLE).notNull());
        database.createTable(LOG,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("delta", Column.Type.DOUBLE).notNull(),
                Column.of("reason", Column.Type.STRING).length(128),
                Column.of("logged_at", Column.Type.LONG).notNull());
        database.execute("DELETE FROM " + database.table(LOG) + " WHERE logged_at < ?", System.currentTimeMillis() - 60L * 86_400_000L);
        bus.on("changed", data -> {
            UUID uuid = UUID.fromString(data.get("uuid").getAsString());
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                apply(player, data.get("points").getAsDouble());
            }
        });
    }

    /** Les actions et conditions d'EterLib (thread principal, au démarrage). */
    public void registerActions(Actions actions) {
        actions.register(lib, "eterlib:reputation", "Ajoute (ou retire) de la réputation au joueur",
                List.of(Argument.required("points"), Argument.optional("raison")), (player, args) -> {
                    double points;
                    try {
                        points = Double.parseDouble(args.getFirst().replace(',', '.'));
                    } catch (NumberFormatException e) {
                        lib.getLogger().warning("eterlib:reputation : nombre illisible " + args.getFirst());
                        return;
                    }
                    String reason = args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : "action";
                    UUID uuid = player.getUniqueId();
                    Tasks.async(lib, () -> add(uuid, points, reason), "Réputation non ajoutée");
                });
        actions.registerCondition(lib, "eterlib:reputation", "Réputation au moins égale (auprès d'un PNJ : × son ratio)",
                List.of(Argument.required("minimum"), Argument.optional("pnj")), (player, args) -> {
                    double minimum;
                    try {
                        minimum = Double.parseDouble(args.getFirst().replace(',', '.'));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                    return args.size() > 1 ? cachedTowards(player.getUniqueId(), args.get(1)) >= minimum
                            : cached(player.getUniqueId()) >= minimum;
                });
    }

    // ---------- Lecture ----------

    /** Bloquant : la réputation du joueur (en mémoire s'il est connecté ici). */
    public double get(UUID player) {
        Double known = online.get(player);
        if (known != null) {
            return known;
        }
        return database.getFirst(TABLE, Map.of("uuid", player)).map(row -> row.getDouble("points")).orElse(0.0);
    }

    /** Sans attendre : celle d'un joueur connecté ici (0 si elle n'est pas encore lue). */
    public double cached(UUID player) {
        return online.getOrDefault(player, 0.0);
    }

    /** Le ratio d'un PNJ (« eternpc:fermier » ou « fermier ») : 1 par défaut. */
    public double ratio(String npc) {
        if (npc == null || npc.isBlank()) {
            return 1;
        }
        try {
            Double ratio = ratios.apply(npc.toLowerCase(Locale.ROOT));
            return ratio == null || ratio < 0 ? 1 : ratio;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Bloquant : la réputation auprès de ce PNJ (réputation × son ratio). */
    public double towards(UUID player, String npc) {
        return get(player) * ratio(npc);
    }

    public double cachedTowards(UUID player, String npc) {
        return cached(player) * ratio(npc);
    }

    /** EterNpc donne le ratio de ses PNJ (null ou négatif : 1). */
    public void setRatioProvider(Function<String, Double> provider) {
        this.ratios = provider == null ? npc -> 1.0 : provider;
    }

    // ---------- Changements ----------

    /** Bloquant : ajoute (ou retire, delta négatif) ; renvoie la nouvelle réputation, prévient les autres serveurs. */
    public double add(UUID player, double delta, String reason) {
        if (delta == 0) {
            return get(player);
        }
        database.execute("INSERT INTO " + database.table(TABLE) + " (uuid, points) VALUES (?, ?) ON DUPLICATE KEY UPDATE points = points + ?",
                player, delta, delta);
        database.insert(LOG, Map.of("uuid", player, "delta", delta, "reason", reason == null ? "" : truncate(reason),
                "logged_at", System.currentTimeMillis()));
        return changed(player);
    }

    /** Bloquant : fixe la réputation (staff). */
    public double set(UUID player, double points, String reason) {
        double before = get(player);
        database.set(TABLE, Map.of("uuid", player, "points", points), "uuid");
        database.insert(LOG, Map.of("uuid", player, "delta", points - before, "reason", reason == null ? "set" : truncate(reason),
                "logged_at", System.currentTimeMillis()));
        return changed(player);
    }

    private double changed(UUID player) {
        double now = database.getFirst(TABLE, Map.of("uuid", player)).map(row -> row.getDouble("points")).orElse(0.0);
        Bukkit.getScheduler().runTask(lib, () -> {
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                apply(online, now);
            }
        });
        JsonObject data = new JsonObject();
        data.addProperty("uuid", player.toString());
        data.addProperty("points", now);
        bus.publish("changed", data);
        return now;
    }

    private void apply(Player player, double points) {
        online.put(player.getUniqueId(), points);
        tags.set(player, TAG, String.valueOf(Math.round(points)));
    }

    private static String truncate(String text) {
        return text.length() > 128 ? text.substring(0, 128) : text;
    }

    // ---------- Joueurs connectés ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Tasks.async(lib, player, () -> database.getFirst(TABLE, Map.of("uuid", uuid)).map(row -> row.getDouble("points")).orElse(0.0),
                points -> apply(player, points), () -> { });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        online.remove(event.getPlayer().getUniqueId());
    }
}
