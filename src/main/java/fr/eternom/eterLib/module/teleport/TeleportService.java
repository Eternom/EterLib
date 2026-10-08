package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.combat.CombatTracker;
import fr.eternom.eterLib.module.server.ServerDirectory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Téléportation commune à tous les plugins Eter (homes, tpa, spawn...) : mêmes règles partout,
 * donc impossible d'enchaîner /home et /tpa pour contourner un cooldown ou fuir un combat.
 *
 * Ordre : combat, puis cooldown, puis attente (bossbar), puis départ. Destination sur un autre serveur :
 * une « téléportation en attente » est enregistrée dans Redis (avec TTL) puis le proxy y envoie le joueur ;
 * le serveur d'arrivée la lit au spawn ({@link TeleportListener}) et le fait apparaître directement au bon endroit.
 */
public class TeleportService {

    public static final String BYPASS_WARMUP = "eter.bypass.warmup";
    public static final String BYPASS_COOLDOWN = "eter.bypass.cooldown";
    public static final String BYPASS_COMBAT = "eter.bypass.combat";

    /** Canal compris par BungeeCord et par Velocity (bungee-plugin-message-channel, activé par défaut). */
    private static final String PROXY_CHANNEL = "BungeeCord";
    /** Délai max entre le départ et l'arrivée sur l'autre serveur. */
    private static final Duration PENDING_TTL = Duration.ofSeconds(30);

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final Messages messages;
    private final String serverName;
    private final ServerDirectory servers;
    private final TeleportWarmup warmup;
    private final TeleportCooldown cooldown;
    private final CombatTracker combat;

    public TeleportService(JavaPlugin plugin, RedisCache redis, Messages messages, String serverName,
                           ServerDirectory servers, TeleportWarmup warmup, TeleportCooldown cooldown, CombatTracker combat) {
        this.plugin = plugin;
        this.redis = redis;
        this.messages = messages;
        this.serverName = serverName;
        this.servers = servers;
        this.warmup = warmup;
        this.cooldown = cooldown;
        this.combat = combat;

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PROXY_CHANNEL);
    }

    /** Point d'entrée des plugins : vérifie les règles puis téléporte. Thread principal. */
    public void teleport(Player player, Destination destination) {
        teleport(player, destination, () -> {
        });
    }

    /**
     * Idem ; onDeparture est lancé (thread principal) seulement si le joueur part vraiment, pas si l'attente est annulée
     * ou la destination inaccessible. Ex : démarrer le délai propre à /rtp.
     */
    public void teleport(Player player, Destination destination, Runnable onDeparture) {
        if (inCombat(player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean checkCooldown = cooldown.isEnabled() && !player.hasPermission(BYPASS_COOLDOWN);
        Tasks.async(plugin, player, () -> checkCooldown ? cooldown.remainingSeconds(uuid) : 0L, remaining -> {
            if (remaining > 0) {
                error(player, "teleport.cooldown", "time", String.valueOf(remaining));
                return;
            }
            warmup.start(player, destination.label(), () -> {
                // Un coup donné ou reçu pendant l'attente compte aussi
                if (inCombat(player) || !move(player, destination)) {
                    return;
                }
                onDeparture.run();
                if (checkCooldown) {
                    Tasks.async(plugin, () -> cooldown.start(uuid), "Cooldown non enregistré pour " + player.getName());
                }
            });
        }, () -> messages.send(player, "error.generic"));
    }

    /** Secondes de combat restantes (0 si le joueur en est dispensé). */
    public long combatSeconds(Player player) {
        return player.hasPermission(BYPASS_COMBAT) ? 0 : combat.remainingSeconds(player.getUniqueId());
    }

    /** Secondes de cooldown restantes (0 si désactivé ou dispensé). Bloquant : hors du thread principal. */
    public long cooldownSeconds(Player player) {
        return !cooldown.isEnabled() || player.hasPermission(BYPASS_COOLDOWN) ? 0 : cooldown.remainingSeconds(player.getUniqueId());
    }

    /**
     * Position d'arrivée si une téléportation est en attente pour ce joueur sur CE serveur.
     * Consomme la téléportation en attente. Bloquant.
     */
    public Optional<Location> takePendingLocation(UUID traveller) {
        return takePending(traveller).filter(destination -> destination.isOn(serverName)).map(Destination::resolve);
    }

    /**
     * Départ immédiat, sans aucune règle (combat, délai, attente) : pour le staff (/tp, /tphere). Thread principal.
     * @return false si la destination est inaccessible
     */
    public boolean teleportNow(Player player, Destination destination) {
        return move(player, destination);
    }

    /** Départ immédiat, sans règles. @return false si la destination est inaccessible */
    private boolean move(Player player, Destination destination) {
        if (destination.isOn(serverName)) {
            Location location = destination.resolve();
            if (location == null) {
                error(player, destination.targetPlayer() != null ? "teleport.target-gone" : "teleport.world-missing",
                        "destination", destination.label());
                return false;
            }
            Bukkit.getPluginManager().callEvent(new EterTeleportEvent(player, player.getLocation(), destination));
            TeleportEffects.burst(player.getLocation());
            player.teleportAsync(location).thenAccept(success -> {
                if (success) {
                    TeleportEffects.burst(location);
                }
            });
            return true;
        }

        Bukkit.getPluginManager().callEvent(new EterTeleportEvent(player, player.getLocation(), destination));
        UUID traveller = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            savePending(traveller, destination);
            return destination.server();
        }, server -> {
            messages.send(player, "teleport.sending", "server", servers.displayName(server));
            TeleportEffects.burst(player.getLocation());
            connect(player, server);
        }, () -> messages.send(player, "error.generic"));
        return true;
    }

    private boolean inCombat(Player player) {
        long remaining = combatSeconds(player);
        if (remaining > 0) {
            error(player, "teleport.in-combat", "time", String.valueOf(remaining));
        }
        return remaining > 0;
    }

    private void error(Player player, String key, String... placeholders) {
        messages.send(player, key, placeholders);
        player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }

    private void savePending(UUID traveller, Destination destination) {
        redis.set(pendingKey(traveller), serialize(destination), PENDING_TTL);
    }

    private Optional<Destination> takePending(UUID traveller) {
        Optional<String> value = redis.get(pendingKey(traveller));
        value.ifPresent(found -> redis.delete(pendingKey(traveller)));
        return value.map(this::deserialize);
    }

    /** Format : target;x;y;z;yaw;pitch;world (monde en dernier, il peut contenir un ';'). */
    private String serialize(Destination destination) {
        return (destination.targetPlayer() == null ? "" : destination.targetPlayer()) + ";" + destination.x() + ";"
                + destination.y() + ";" + destination.z() + ";" + destination.yaw() + ";" + destination.pitch() + ";"
                + (destination.world() == null ? "" : destination.world());
    }

    private Destination deserialize(String value) {
        String[] parts = value.split(";", 7);
        return new Destination(serverName, parts[6].isEmpty() ? null : parts[6], Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Float.parseFloat(parts[4]),
                Float.parseFloat(parts[5]), parts[0].isEmpty() ? null : UUID.fromString(parts[0]), "");
    }

    /**
     * Envoie le joueur sur un autre serveur, sans règle de téléportation (attente, combat, cooldown) ni position :
     * il arrive où ce serveur place les nouveaux venus (ex : sélecteur de serveurs d'un lobby).
     */
    public void connect(Player player, String server) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            throw new IllegalStateException(e); // impossible en mémoire
        }
        player.sendPluginMessage(plugin, PROXY_CHANNEL, bytes.toByteArray());
    }

    private String pendingKey(UUID traveller) {
        return "pending:" + traveller;
    }
}
