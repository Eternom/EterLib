package fr.eternom.eterLib.module.teleport;

import fr.eternom.eterLib.helper.cache.Cooldowns;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.combat.CombatTracker;
import fr.eternom.eterLib.module.server.ServerDirectory;
import fr.eternom.eterLib.module.teleport.TeleportOptions.Failure;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Téléportation commune à tous les plugins Eter (homes, tpa, spawn...) : mêmes règles partout,
 * donc impossible d'enchaîner /home et /tpa pour contourner un cooldown ou fuir un combat.
 *
 * Ordre : combat, puis cooldown, puis attente (bossbar, compte à rebours), puis départ. Destination sur un autre
 * serveur : une « téléportation en attente » est enregistrée dans Redis (avec TTL) puis le proxy y envoie le joueur ;
 * le serveur d'arrivée la lit au spawn ({@link TeleportListener}) et le fait apparaître directement au bon endroit.
 * Vers un joueur, l'arrivée est déplacée sur le bloc sûr le plus proche (lave, vide, suffocation : {@link SafeLocation}),
 * et le voyageur est protégé teleport.arrival-protection secondes (tant qu'il ne frappe pas).
 * Cooldown : teleport.cooldown, ou moins avec eter.teleport.cooldown.<secondes> (la plus petite l'emporte).
 */
public class TeleportService {

    public static final String BYPASS_WARMUP = "eter.bypass.warmup";
    public static final String BYPASS_COOLDOWN = "eter.bypass.cooldown";
    public static final String BYPASS_COMBAT = "eter.bypass.combat";
    public static final String COOLDOWN_PREFIX = "eter.teleport.cooldown.";

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
    private final Cooldowns cooldown;
    private final CombatTracker combat;
    private final long protectionMillis;
    /** Joueurs protégés après leur arrivée, jusqu'à quand. */
    private final Map<UUID, Long> protectedUntil = new ConcurrentHashMap<>();

    public TeleportService(JavaPlugin plugin, RedisCache redis, Messages messages, String serverName,
                           ServerDirectory servers, TeleportWarmup warmup, Cooldowns cooldown, CombatTracker combat) {
        this(plugin, redis, messages, serverName, servers, warmup, cooldown, combat, Duration.ofSeconds(3));
    }

    public TeleportService(JavaPlugin plugin, RedisCache redis, Messages messages, String serverName,
                           ServerDirectory servers, TeleportWarmup warmup, Cooldowns cooldown, CombatTracker combat,
                           Duration arrivalProtection) {
        this.plugin = plugin;
        this.redis = redis;
        this.messages = messages;
        this.serverName = serverName;
        this.servers = servers;
        this.warmup = warmup;
        this.cooldown = cooldown;
        this.combat = combat;
        this.protectionMillis = arrivalProtection.toMillis();

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PROXY_CHANNEL);
    }

    /** Point d'entrée des plugins : vérifie les règles puis téléporte. Thread principal. */
    public void teleport(Player player, Destination destination) {
        teleport(player, destination, TeleportOptions.defaults());
    }

    /**
     * Idem ; onDeparture est lancé (thread principal) seulement si le joueur part vraiment, pas si l'attente est annulée
     * ou la destination inaccessible. Ex : démarrer le délai propre à /rtp.
     */
    public void teleport(Player player, Destination destination, Runnable onDeparture) {
        teleport(player, destination, TeleportOptions.defaults().onDeparture(onDeparture));
    }

    /** Avec des réglages (annulation au mouvement, sans attente, rappels de départ et d'échec). Thread principal. */
    public void teleport(Player player, Destination destination, TeleportOptions options) {
        if (inCombat(player)) {
            options.onFailed().accept(Failure.COMBAT);
            return;
        }
        if (warmup.isRunning(player.getUniqueId())) {
            messages.send(player, "teleport.busy");
            options.onFailed().accept(Failure.BUSY);
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean checkCooldown = cooldown.isEnabled() && !player.hasPermission(BYPASS_COOLDOWN);
        Duration playerCooldown = cooldownFor(player);
        Tasks.async(plugin, player, () -> checkCooldown ? cooldown.remainingSeconds(uuid) : 0L, remaining -> {
            if (remaining > 0) {
                error(player, "teleport.cooldown", "time", String.valueOf(remaining));
                options.onFailed().accept(Failure.COOLDOWN);
                return;
            }
            Runnable depart = () -> {
                // Un coup donné ou reçu pendant l'attente compte aussi
                if (inCombat(player)) {
                    options.onFailed().accept(Failure.COMBAT);
                    return;
                }
                if (!move(player, destination)) {
                    options.onFailed().accept(Failure.UNREACHABLE);
                    return;
                }
                options.onDeparture().run();
                if (checkCooldown) {
                    Tasks.async(plugin, () -> cooldown.start(uuid, playerCooldown), "Cooldown non enregistré pour " + player.getName());
                }
            };
            if (options.skipWarmup()) {
                depart.run();
            } else if (!warmup.start(player, destination.label(), options.cancelOnMove(), depart,
                    () -> options.onFailed().accept(Failure.CANCELLED))) {
                options.onFailed().accept(Failure.BUSY);
            }
        }, () -> {
            messages.send(player, "error.generic");
            options.onFailed().accept(Failure.UNREACHABLE);
        });
    }

    /** Le cooldown de ce joueur : la plus petite permission eter.teleport.cooldown.<n>, sinon la config. */
    public Duration cooldownFor(Player player) {
        Duration best = cooldown.duration();
        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            String name = permission.getPermission();
            if (permission.getValue() && name.startsWith(COOLDOWN_PREFIX)) {
                try {
                    Duration custom = Duration.ofSeconds(Math.max(0, Long.parseLong(name.substring(COOLDOWN_PREFIX.length()))));
                    if (custom.compareTo(best) < 0) {
                        best = custom;
                    }
                } catch (NumberFormatException ignored) {
                    // permission mal écrite : ignorée
                }
            }
        }
        return best;
    }

    /** Secondes de combat restantes (0 si le joueur en est dispensé). */
    public long combatSeconds(Player player) {
        return player.hasPermission(BYPASS_COMBAT) ? 0 : combat.remainingSeconds(player.getUniqueId());
    }

    /** Secondes de cooldown restantes (0 si désactivé ou dispensé). Bloquant : hors du thread principal. */
    public long cooldownSeconds(Player player) {
        return !cooldown.isEnabled() || player.hasPermission(BYPASS_COOLDOWN) ? 0 : cooldown.remainingSeconds(player.getUniqueId());
    }

    /** L'attente de ce joueur avant un départ, en secondes (0 = sans attente). */
    public int warmupSeconds(Player player) {
        return (warmup.ticksFor(player) + 19) / 20;
    }

    /**
     * Position d'arrivée si une téléportation est en attente pour ce joueur sur CE serveur (sûre si c'est vers un
     * joueur). Consomme la téléportation en attente. Bloquant.
     */
    public Optional<Location> takePendingLocation(UUID traveller) {
        return takePending(traveller).filter(destination -> destination.isOn(serverName)).map(destination -> {
            Location location = destination.resolve();
            return location != null && destination.targetPlayer() != null ? SafeLocation.find(location) : location;
        });
    }

    /**
     * Départ immédiat, sans aucune règle (combat, délai, attente) : pour le staff (/tp, /tphere). Thread principal.
     * @return false si la destination est inaccessible
     */
    public boolean teleportNow(Player player, Destination destination) {
        return move(player, destination);
    }

    /** Protégé après son arrivée (n'importe quel dégât est annulé, tant qu'il ne frappe pas). */
    public boolean isProtected(UUID player) {
        Long until = protectedUntil.get(player);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            protectedUntil.remove(player);
            return false;
        }
        return true;
    }

    /** Thread principal : protégé quelques secondes (arrivée). */
    public void protect(UUID player) {
        if (protectionMillis > 0) {
            protectedUntil.put(player, System.currentTimeMillis() + protectionMillis);
        }
    }

    public void unprotect(UUID player) {
        protectedUntil.remove(player);
    }

    /** Départ immédiat, sans règles. @return false si la destination est inaccessible */
    private boolean move(Player player, Destination destination) {
        if (destination.isOn(serverName)) {
            Location wanted = destination.resolve();
            if (wanted == null) {
                error(player, destination.targetPlayer() != null ? "teleport.target-gone" : "teleport.world-missing",
                        "destination", destination.label());
                return false;
            }
            Location location = destination.targetPlayer() != null ? SafeLocation.find(wanted) : wanted;
            Bukkit.getPluginManager().callEvent(new EterTeleportEvent(player, player.getLocation(), destination));
            TeleportEffects.burst(player, player.getLocation());
            player.teleportAsync(location).thenAccept(success -> {
                if (success) {
                    TeleportEffects.burst(player, location);
                    if (destination.targetPlayer() != null) {
                        protect(player.getUniqueId());
                    }
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
            TeleportEffects.burst(player, player.getLocation());
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
        Sounds.refuse(player);
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
