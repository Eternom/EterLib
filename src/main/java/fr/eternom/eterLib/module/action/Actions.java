package fr.eternom.eterLib.module.action;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.combat.CombatTracker;
import fr.eternom.eterLib.module.server.ServerDirectory;
import fr.eternom.eterLib.module.teleport.TeleportService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Les actions du réseau : chaque plugin déclare les siennes (« eterjob:quests », « eterlib:message »...), et n'importe
 * quel PNJ, menu ou commande peut les lancer par une ligne de texte (ActionLine), sans connaître le plugin.
 *
 * - Une action déclarée ici s'exécute ici. Sinon, EterLib cherche un serveur qui la fournit (annuaire Redis, rafraîchi
 *   toutes les 20 s) : la suite de la chaîne y est mise de côté, le joueur y est envoyé, et elle se lance à son arrivée.
 * - Une ligne « ?condition » arrête la chaîne si elle est fausse (« ?! » : si elle est vraie).
 * - Les arguments sont vérifiés à l'enregistrement (validate) et complétés dans les commandes (complete).
 * Thread principal, sauf l'annuaire (tâche de fond).
 */
public final class Actions implements Listener {

    /** Ce que fait une action (thread principal). args : déjà remplis ({player}...). */
    @FunctionalInterface
    public interface Handler {
        void run(Player player, List<String> args);
    }

    /** Une condition (thread principal). */
    @FunctionalInterface
    public interface Condition {
        boolean test(Player player, List<String> args);
    }

    /** Un argument d'action : son nom (aide), obligatoire ou non, et ses valeurs proposées (complétion, peut être vide). */
    public record Argument(String name, boolean required, Function<String, List<String>> completions) {

        public static Argument required(String name) {
            return new Argument(name, true, typed -> List.of());
        }

        public static Argument optional(String name) {
            return new Argument(name, false, typed -> List.of());
        }

        public static Argument required(String name, Function<String, List<String>> completions) {
            return new Argument(name, true, completions);
        }
    }

    /** Une action ou une condition connue (ici ou sur le réseau). */
    public record Definition(String id, String plugin, String description, List<String> arguments, int required,
                             boolean condition) {

        /** « eterjob:quests <métier> [page] » */
        public String usage() {
            StringBuilder usage = new StringBuilder(condition ? "?" + id : id);
            for (int i = 0; i < arguments.size(); i++) {
                usage.append(i < required ? " <" : " [").append(arguments.get(i)).append(i < required ? ">" : "]");
            }
            return usage.toString();
        }
    }

    private record Registered(JavaPlugin plugin, Definition definition, List<Argument> arguments, Handler handler,
                              Condition condition) {
    }

    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PENDING_TTL = Duration.ofMinutes(2);
    private static final String INDEX = "actions:index";
    private static final long REFRESH_TICKS = 20 * 20;
    /** Délai après l'arrivée avant de lancer la suite (inventaire et menus chargés). */
    private static final long ARRIVAL_DELAY_TICKS = 30;

    private final JavaPlugin lib;
    private final RedisCache redis;
    private final Messages messages;
    private final String serverName;
    private final ServerDirectory servers;
    private final TeleportService teleports;
    private final CombatTracker combat;
    private final boolean debug;
    private final Map<String, Registered> local = new ConcurrentHashMap<>();
    /** Annuaire du réseau (actions des autres serveurs), relu toutes les 20 s. */
    private volatile Map<String, Definition> network = Map.of();
    /** Derniers déclenchements (délais par joueur) : joueur|clé -> fin du délai. */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public Actions(JavaPlugin lib, RedisCache redis, Messages messages, String serverName, ServerDirectory servers,
                   TeleportService teleports, CombatTracker combat, boolean debug) {
        this.lib = lib;
        this.redis = redis;
        this.messages = messages;
        this.serverName = serverName;
        this.servers = servers;
        this.teleports = teleports;
        this.combat = combat;
        this.debug = debug;
        BuiltinActions.register(this, lib, messages, teleports, combat);
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(lib, this::refresh, 40, REFRESH_TICKS);
    }

    public void stop() {
        for (String id : local.keySet()) {
            try {
                redis.deleteHashField(providersKey(id), serverName);
            } catch (RuntimeException ignored) {
                // Redis déjà fermé : l'entrée expire d'elle-même (60 s)
            }
        }
    }

    // ---------- Déclaration ----------

    /** Déclare une action de plugin (« eterjob:quests ») ; remplace celle du même identifiant. Thread principal. */
    public void register(JavaPlugin plugin, String id, String description, List<Argument> arguments, Handler handler) {
        add(plugin, id, description, arguments, handler, null);
    }

    /** Déclare une condition (« ?eterjob:has-job mineur »). */
    public void registerCondition(JavaPlugin plugin, String id, String description, List<Argument> arguments, Condition condition) {
        add(plugin, id, description, arguments, null, condition);
    }

    private void add(JavaPlugin plugin, String id, String description, List<Argument> arguments, Handler handler, Condition condition) {
        String key = id.toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_-]+:[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Identifiant d'action invalide (plugin:action) : " + id);
        }
        int required = 0;
        while (required < arguments.size() && arguments.get(required).required()) {
            required++;
        }
        Definition definition = new Definition(key, plugin.getName(), description,
                arguments.stream().map(Argument::name).toList(), required, condition != null);
        local.put(key, new Registered(plugin, definition, List.copyOf(arguments), handler, condition));
    }

    /** Un plugin s'arrête : ses actions disparaissent d'ici (et de l'annuaire au prochain rafraîchissement). */
    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        List<String> gone = local.values().stream().filter(registered -> registered.plugin() == event.getPlugin())
                .map(registered -> registered.definition().id()).toList();
        gone.forEach(local::remove);
        if (!gone.isEmpty() && event.getPlugin() != lib) {
            Bukkit.getScheduler().runTaskAsynchronously(lib, () -> gone.forEach(id -> {
                try {
                    redis.deleteHashField(providersKey(id), serverName);
                } catch (RuntimeException ignored) {
                    // l'entrée expire d'elle-même
                }
            }));
        }
    }

    /** Toutes les actions connues : celles d'ici, puis celles du réseau. */
    public Collection<Definition> known() {
        Map<String, Definition> all = new LinkedHashMap<>(network);
        local.values().forEach(registered -> all.put(registered.definition().id(), registered.definition()));
        return all.values().stream().sorted(Comparator.comparing(Definition::id)).toList();
    }

    public Optional<Definition> definition(String id) {
        Registered registered = local.get(id);
        return registered != null ? Optional.of(registered.definition()) : Optional.ofNullable(network.get(id));
    }

    public boolean isLocal(String id) {
        return local.containsKey(id);
    }

    // ---------- Vérification et complétion ----------

    /**
     * Les erreurs d'une chaîne (ligne illisible, action inconnue du réseau, argument manquant), dans la langue de viewer ;
     * vide si tout est bon. À appeler avant d'enregistrer une chaîne (éditeur de PNJ...).
     */
    public List<String> validate(org.bukkit.command.CommandSender viewer, List<String> lines) {
        List<String> errors = new ArrayList<>();
        for (String text : lines) {
            ActionLine line;
            try {
                line = ActionLine.parse(text);
            } catch (IllegalArgumentException e) {
                errors.add(messages.plain(viewer, "action.invalid", "line", text, "reason", e.getMessage()));
                continue;
            }
            Optional<Definition> definition = definition(line.id());
            if (definition.isEmpty()) {
                errors.add(messages.plain(viewer, "action.unknown", "action", line.id()));
            } else if (definition.get().condition() != line.condition()) {
                errors.add(messages.plain(viewer, definition.get().condition() ? "action.is-condition" : "action.not-condition",
                        "action", line.id()));
            } else if (line.args().size() < definition.get().required()) {
                errors.add(messages.plain(viewer, "action.missing-args", "usage", definition.get().usage()));
            }
        }
        return errors;
    }

    /** Complétion d'une ligne en cours d'écriture (mots déjà tapés) : identifiants, puis valeurs des arguments. */
    public List<String> complete(List<String> words) {
        if (words.isEmpty()) {
            return List.of();
        }
        int index = 0;
        if (words.getFirst().startsWith("@")) {
            if (words.size() == 1) {
                return List.of();
            }
            index = 1;
        }
        String head = words.get(index).toLowerCase(Locale.ROOT);
        if (words.size() == index + 1) {
            String prefix = head.startsWith("?!") ? "?!" : head.startsWith("?") ? "?" : "";
            String typed = head.substring(prefix.length());
            return known().stream().filter(definition -> definition.condition() == !prefix.isEmpty())
                    .map(Definition::id).filter(id -> id.startsWith(typed)).map(id -> prefix + id).toList();
        }
        String id = head.replaceFirst("^\\?!?", "");
        Registered registered = local.get(id);
        int argument = words.size() - index - 2;
        if (registered == null || argument >= registered.arguments().size()) {
            return List.of();
        }
        String typed = words.getLast().toLowerCase(Locale.ROOT);
        return registered.arguments().get(argument).completions().apply(typed).stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    // ---------- Exécution ----------

    /**
     * Lance une chaîne pour le joueur, avec un délai par joueur (clé libre, ex : « eternpc:guilde ») : false si le délai
     * n'est pas écoulé (rien n'est lancé, le joueur est prévenu). Thread principal.
     */
    public boolean run(Player player, List<String> lines, String cooldownKey, Duration cooldown) {
        if (cooldownKey != null && !cooldown.isZero()) {
            String key = player.getUniqueId() + "|" + cooldownKey;
            long now = System.currentTimeMillis();
            Long until = cooldowns.get(key);
            if (until != null && until > now) {
                messages.actionBar(player, "action.cooldown", "time", String.valueOf(Math.max(1, (until - now + 999) / 1000)));
                return false;
            }
            cooldowns.put(key, now + cooldown.toMillis());
        }
        run(player, lines);
        return true;
    }

    /** Lance une chaîne pour le joueur (thread principal). */
    public void run(Player player, List<String> lines) {
        Map<String, String> values = Map.of("player", player.getName(), "uuid", player.getUniqueId().toString(),
                "server", serverName, "world", player.getWorld().getName());
        for (int i = 0; i < lines.size(); i++) {
            ActionLine line;
            try {
                line = ActionLine.parse(lines.get(i));
            } catch (IllegalArgumentException e) {
                lib.getLogger().warning("Action illisible (" + lines.get(i) + ") : " + e.getMessage());
                messages.send(player, "action.error");
                return;
            }
            Registered registered = local.get(line.id());
            boolean elsewhere = line.server() != null && !line.server().equalsIgnoreCase(serverName);
            if (registered == null || elsewhere) {
                travel(player, line, lines.subList(i, lines.size()));
                return;
            }
            List<String> args = line.resolve(values);
            if (debug) {
                lib.getLogger().info("[actions] " + player.getName() + " : " + line.format());
            }
            try {
                if (registered.condition() != null) {
                    if (registered.condition().test(player, args) == line.negate()) {
                        return; // condition non remplie : la suite n'est pas lancée
                    }
                } else if (registered.handler() != null) {
                    registered.handler().run(player, args);
                }
            } catch (RuntimeException e) {
                lib.getLogger().severe("Action " + line.id() + " en erreur pour " + player.getName() + " : " + e);
                messages.send(player, "action.error");
                return;
            }
        }
    }

    /**
     * Thread principal : ces conditions (« ?eterjob:rank mineur expert », « ?!... ») sont-elles toutes vraies, pour ce
     * joueur ? Une condition qui n'est pas déclarée ici, ou illisible, compte comme fausse. Depuis 1.25.0.
     */
    public boolean test(Player player, List<String> conditions) {
        Map<String, String> values = Map.of("player", player.getName(), "uuid", player.getUniqueId().toString(),
                "server", serverName, "world", player.getWorld().getName());
        for (String text : conditions) {
            String condition = text.startsWith("?") ? text : "?" + text;
            ActionLine line;
            try {
                line = ActionLine.parse(condition);
            } catch (IllegalArgumentException e) {
                lib.getLogger().warning("Condition illisible (" + text + ") : " + e.getMessage());
                return false;
            }
            Registered registered = local.get(line.id());
            if (registered == null || registered.condition() == null) {
                return false;
            }
            try {
                if (registered.condition().test(player, line.resolve(values)) == line.negate()) {
                    return false;
                }
            } catch (RuntimeException e) {
                lib.getLogger().severe("Condition " + line.id() + " en erreur pour " + player.getName() + " : " + e);
                return false;
            }
        }
        return true;
    }

    /**
     * L'action n'est pas ici (ou un autre serveur est imposé) : la suite de la chaîne est mise de côté, et le joueur est
     * envoyé sur un serveur qui la fournit ; elle se lance à son arrivée.
     */
    private void travel(Player player, ActionLine line, List<String> rest) {
        if (combat.remainingSeconds(player.getUniqueId()) > 0) {
            messages.send(player, "teleport.in-combat", "time", String.valueOf(combat.remainingSeconds(player.getUniqueId())));
            return;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(lib, () -> {
            String target = line.server() != null ? line.server() : provider(line.id()).orElse(null);
            Bukkit.getScheduler().runTask(lib, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (target == null || line.server() != null && !servers.isOnline(target)) {
                    messages.send(player, "action.unavailable", "action", line.id());
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(lib, () -> {
                    redis.set(pendingKey(uuid), String.join("\n", rest), PENDING_TTL);
                    Bukkit.getScheduler().runTask(lib, () -> {
                        if (player.isOnline()) {
                            messages.send(player, "teleport.sending", "server", servers.displayName(target));
                            teleports.connect(player, target);
                        }
                    });
                });
            });
        });
    }

    /** Un serveur qui fournit l'action (vu il y a moins de 60 s), autre que celui-ci. Bloquant. */
    private Optional<String> provider(String id) {
        long now = System.currentTimeMillis();
        return redis.getHash(providersKey(id)).entrySet().stream()
                .filter(entry -> !entry.getKey().equalsIgnoreCase(serverName))
                .filter(entry -> now - parse(entry.getValue()) < PROVIDER_TIMEOUT.toMillis())
                .max(Comparator.comparingLong(entry -> parse(entry.getValue())))
                .map(Map.Entry::getKey);
    }

    /** Arrivée : la suite d'une chaîne lancée ailleurs, s'il y en a une. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(lib, () -> {
            Optional<String> pending;
            try {
                pending = redis.get(pendingKey(uuid));
                pending.ifPresent(value -> redis.delete(pendingKey(uuid)));
            } catch (RuntimeException e) {
                return;
            }
            pending.ifPresent(value -> Bukkit.getScheduler().runTaskLater(lib, () -> {
                if (player.isOnline()) {
                    run(player, List.of(value.split("\n")));
                }
            }, ARRIVAL_DELAY_TICKS));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String prefix = event.getPlayer().getUniqueId() + "|";
        cooldowns.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ---------- Annuaire (tâche de fond) ----------

    /** Annonce les actions d'ici, et relit celles du réseau. */
    private void refresh() {
        try {
            String now = String.valueOf(System.currentTimeMillis());
            Map<String, String> index = new HashMap<>();
            for (Registered registered : local.values()) {
                Definition definition = registered.definition();
                redis.setHashField(providersKey(definition.id()), serverName, now);
                redis.expire(providersKey(definition.id()), PROVIDER_TIMEOUT.multipliedBy(5));
                index.put(definition.id(), encode(definition));
            }
            if (!index.isEmpty()) {
                redis.setHash(INDEX, index);
            }
            Map<String, Definition> read = new HashMap<>();
            long limit = System.currentTimeMillis() - PROVIDER_TIMEOUT.toMillis();
            redis.getHash(INDEX).forEach((id, value) -> {
                // Plus aucun serveur ne la fournit depuis une minute : retirée de l'annuaire
                boolean alive = local.containsKey(id)
                        || redis.getHash(providersKey(id)).values().stream().anyMatch(seen -> parse(seen) > limit);
                if (alive) {
                    decode(id, value).ifPresent(definition -> read.put(id, definition));
                } else {
                    redis.deleteHashField(INDEX, id);
                }
            });
            network = Map.copyOf(read);
        } catch (RuntimeException e) {
            lib.getLogger().warning("Annuaire des actions non mis à jour : " + e.getMessage());
        }
    }

    private static String encode(Definition definition) {
        return String.join("\u0001", definition.plugin(), definition.condition() ? "1" : "0", String.valueOf(definition.required()),
                definition.description(), String.join("\u0002", definition.arguments()));
    }

    private static Optional<Definition> decode(String id, String value) {
        String[] parts = value.split("\u0001", -1);
        if (parts.length < 5) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Definition(id, parts[0], parts[3], parts[4].isEmpty() ? List.of() : List.of(parts[4].split("\u0002")),
                    Integer.parseInt(parts[2]), parts[1].equals("1")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String providersKey(String id) {
        return "actions:providers:" + id;
    }

    private static String pendingKey(UUID player) {
        return "actions:pending:" + player;
    }
}
