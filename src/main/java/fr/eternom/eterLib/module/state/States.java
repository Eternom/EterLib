package fr.eternom.eterLib.module.state;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;

/**
 * Les états du réseau (saignement, étourdissement...), sur toute entité vivante, joueur ou monstre : une seule API pour
 * EterMob et les futures classes. En mémoire seulement (un état ne survit pas à un changement de serveur ni à un
 * redémarrage) ; thread principal. Depuis EterLib 1.26.0.
 * <ul>
 *   <li>apply : ténacité (contrôles), immunités, StateApplyEvent, cumul (StateRules), montée (gel -> étourdissement) ;</li>
 *   <li>effets : vitesse, saut, armure (modificateurs temporaires, jamais sauvegardés), dégâts infligés et reçus, coups et
 *   tirs bloqués, dégâts sur la durée (avec leur auteur : le kill lui revient), effets particuliers (peur, provocation) ;</li>
 *   <li>après un contrôle, une courte immunité aux contrôles (pas de chaîne d'étourdissements) ;</li>
 *   <li>affichage : barre d'action des joueurs, particules autour des monstres.</li>
 * </ul>
 * Une capacité vérifie canUseAbilities (silence, étourdissement) ; une IA lit taunted (provocation).
 */
public final class States implements Listener {

    private static final int TICK = 5;

    private final JavaPlugin plugin;
    private final Map<String, StateType> types = new LinkedHashMap<>();
    private final Map<UUID, Map<String, StateInstance>> holders = new HashMap<>();
    private final Map<UUID, Long> controlImmuneUntil = new HashMap<>();
    private final List<ToDoubleFunction<LivingEntity>> tenacity = new CopyOnWriteArrayList<>();
    private final List<ToDoubleBiFunction<LivingEntity, StateType>> immunities = new CopyOnWriteArrayList<>();
    private final NamespacedKey key;
    private final double maxTenacity;
    private final long controlImmunityMillis;
    private final boolean actionBar;
    private boolean dealing;
    private fr.eternom.eterLib.module.combat.CombatSystem combat;
    private long ticks;

    public States(JavaPlugin plugin, double maxTenacity, double controlImmunitySeconds, boolean actionBar) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "states");
        this.maxTenacity = maxTenacity;
        this.controlImmunityMillis = Math.round(Math.max(0, controlImmunitySeconds) * 1000);
        this.actionBar = actionBar;
    }

    /** Le combat (dégâts typés des états) : posé par EterLib au démarrage. */
    public void setCombat(fr.eternom.eterLib.module.combat.CombatSystem combat) {
        this.combat = combat;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK, TICK);
    }

    // ---------- Registre ----------

    /** Déclare un état (au démarrage) ; un identifiant déjà pris est refusé. */
    public void register(StateType type) {
        if (types.putIfAbsent(type.id(), type) != null) {
            throw new IllegalArgumentException("État déjà déclaré : " + type.id());
        }
    }

    public StateType type(String id) {
        return id == null ? null : types.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<StateType> types() {
        return Collections.unmodifiableCollection(types.values());
    }

    /** Valeur d'équilibrage d'une pose (0 si l'état est inconnu) : de quoi comparer deux capacités. */
    public double value(String id, double power, double seconds, int stacks) {
        StateType type = type(id);
        return type == null ? 0 : type.value(power, seconds, stacks);
    }

    /** Ténacité d'une entité (somme des fournisseurs : EterMob pour les monstres, classes pour les joueurs). */
    public void addTenacity(ToDoubleFunction<LivingEntity> provider) {
        tenacity.add(provider);
    }

    /** Immunités : facteur de durée (0 = immunisé, 0,5 = moitié), produit de tous les fournisseurs. */
    public void addImmunity(ToDoubleBiFunction<LivingEntity, StateType> provider) {
        immunities.add(provider);
    }

    public double tenacity(LivingEntity entity) {
        double total = 0;
        for (ToDoubleFunction<LivingEntity> provider : tenacity) {
            total += provider.applyAsDouble(entity);
        }
        return Math.clamp(total, 0, maxTenacity);
    }

    private double immunity(LivingEntity entity, StateType type) {
        double factor = 1;
        for (ToDoubleBiFunction<LivingEntity, StateType> provider : immunities) {
            factor *= Math.clamp(provider.applyAsDouble(entity, type), 0, 1);
        }
        return factor;
    }

    // ---------- Poser, retirer, lire ----------

    /**
     * Pose un état : power (sens propre à l'état), seconds, source (null : l'environnement). Renvoie false si rien n'a été
     * posé (inconnu, immunisé, ténacité totale, annulé, créatif ou spectateur).
     */
    public boolean apply(LivingEntity target, String id, double power, double seconds, Entity source) {
        StateType type = type(id);
        if (type == null || target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (target instanceof Player player && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long duration = Math.round(seconds * 1000);
        if (type.category() == StateType.Category.CONTROL) {
            if (controlImmuneUntil.getOrDefault(target.getUniqueId(), 0L) > now) {
                return false;
            }
            duration = StateRules.controlDuration(duration, tenacity(target), maxTenacity, immunity(target, type));
        } else {
            duration = Math.round(duration * immunity(target, type));
        }
        if (duration <= 0) {
            return false;
        }
        StateApplyEvent event = new StateApplyEvent(target, type, source, power, duration);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getDurationMillis() <= 0) {
            return false;
        }
        Map<String, StateInstance> states = holders.computeIfAbsent(target.getUniqueId(), uuid -> new LinkedHashMap<>());
        StateInstance current = states.get(type.id());
        UUID sourceId = source == null ? null : source.getUniqueId();
        StateRules.Merge merge = StateRules.merge(type.stacking(), type.maxStacks(),
                current == null ? 0 : current.power(), current == null ? 0 : current.stacks(), current == null ? 0 : current.endsAt(),
                current != null, event.getPower(), event.getDurationMillis(), now);
        if (current == null) {
            states.put(type.id(), new StateInstance(type, merge.power(), merge.stacks(), merge.endsAt(), sourceId, now));
        } else {
            current.set(merge.power(), merge.stacks(), merge.endsAt(), sourceId);
        }
        if (type.escalateTo() != null && type.stacking() == StateType.Stacking.STACK && merge.stacks() >= type.maxStacks()) {
            StateInstance removed = states.remove(type.id());
            Bukkit.getPluginManager().callEvent(new StateExpireEvent(target, removed, StateExpireEvent.Reason.ESCALATED));
            refresh(target);
            apply(target, type.escalateTo(), 1, type.escalateSeconds(), source);
            return true;
        }
        refresh(target);
        return true;
    }

    public boolean has(LivingEntity entity, String id) {
        return get(entity, id) != null;
    }

    public StateInstance get(LivingEntity entity, String id) {
        Map<String, StateInstance> states = entity == null ? null : holders.get(entity.getUniqueId());
        StateInstance state = states == null ? null : states.get(id);
        return state != null && state.endsAt() > System.currentTimeMillis() ? state : null;
    }

    /** Les états actifs d'une entité (copie). */
    public List<StateInstance> active(LivingEntity entity) {
        Map<String, StateInstance> states = entity == null ? null : holders.get(entity.getUniqueId());
        if (states == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return states.values().stream().filter(state -> state.endsAt() > now).toList();
    }

    public boolean remove(LivingEntity entity, String id) {
        Map<String, StateInstance> states = holders.get(entity.getUniqueId());
        StateInstance removed = states == null ? null : states.remove(id);
        if (removed == null) {
            return false;
        }
        end(entity, removed, StateExpireEvent.Reason.REMOVED);
        refresh(entity);
        return true;
    }

    /** Retire tout (ou seulement les purgeables) ; renvoie le nombre d'états retirés. */
    public int clear(LivingEntity entity, StateExpireEvent.Reason reason) {
        Map<String, StateInstance> states = holders.get(entity.getUniqueId());
        if (states == null) {
            return 0;
        }
        int count = 0;
        for (Iterator<StateInstance> it = states.values().iterator(); it.hasNext(); ) {
            StateInstance state = it.next();
            if (reason == StateExpireEvent.Reason.PURGED && !state.type().purgeable()) {
                continue;
            }
            it.remove();
            count++;
            if (reason != StateExpireEvent.Reason.DEATH) {
                end(entity, state, reason);
            } else {
                Bukkit.getPluginManager().callEvent(new StateExpireEvent(entity, state, reason));
            }
        }
        if (states.isEmpty()) {
            holders.remove(entity.getUniqueId());
        }
        refresh(entity);
        return count;
    }

    public boolean canMove(LivingEntity entity) {
        return active(entity).stream().noneMatch(state -> state.type().blocksMove());
    }

    public boolean canAttack(LivingEntity entity) {
        return active(entity).stream().noneMatch(state -> state.type().blocksAttack());
    }

    /** Silence, étourdissement : pas de capacité (monstres, classes). */
    public boolean canUseAbilities(LivingEntity entity) {
        return active(entity).stream().noneMatch(state -> state.type().blocksAbilities());
    }

    /** Provocation : l'entité qui oblige ce monstre à la viser, ou null. */
    public LivingEntity taunted(LivingEntity mob) {
        StateInstance taunt = get(mob, "taunt");
        return taunt == null || taunt.source() == null ? null
                : Bukkit.getEntity(taunt.source()) instanceof LivingEntity source && source.isValid() ? source : null;
    }

    /** Part de l'armure qui reste (fragilité) : lue par le combat. */
    public double armorFactor(LivingEntity entity) {
        double factor = 1;
        for (StateInstance state : active(entity)) {
            if (state.type().armor() > 0) {
                factor *= StateRules.speedFactor(state.type().armor(), state.amount());
            }
        }
        return factor;
    }

    /** Part de la résistance magique qui reste (brisure) : lue par le combat. */
    public double magicResistFactor(LivingEntity entity) {
        double factor = 1;
        for (StateInstance state : active(entity)) {
            if (state.type().magicResist() > 0) {
                factor *= StateRules.speedFactor(state.type().magicResist(), state.amount());
            }
        }
        return factor;
    }

    public double damageDealtFactor(LivingEntity entity) {
        double factor = 1;
        for (StateInstance state : active(entity)) {
            if (state.type().damageDealt() != 0) {
                factor *= StateRules.damageFactor(state.type().damageDealt(), state.amount());
            }
        }
        return factor;
    }

    public double damageTakenFactor(LivingEntity entity) {
        double factor = 1;
        for (StateInstance state : active(entity)) {
            if (state.type().damageTaken() != 0) {
                factor *= StateRules.damageFactor(state.type().damageTaken(), state.amount());
            }
        }
        return factor;
    }

    /** Vrai pendant les dégâts d'un état (saignement...) : à ignorer par ceux qui réagissent aux coups (capacités « au coup »). */
    public boolean isStateDamage() {
        return dealing;
    }

    // ---------- Effets ----------

    private void end(LivingEntity entity, StateInstance state, StateExpireEvent.Reason reason) {
        if (state.type().category() == StateType.Category.CONTROL && controlImmunityMillis > 0) {
            controlImmuneUntil.put(entity.getUniqueId(), System.currentTimeMillis() + controlImmunityMillis);
        }
        Bukkit.getPluginManager().callEvent(new StateExpireEvent(entity, state, reason));
    }

    /** Remet les modificateurs (vitesse, saut, armure) d'après les états actifs. */
    void refresh(LivingEntity entity) {
        double speed = 1;
        boolean rooted = false;
        for (StateInstance state : active(entity)) {
            StateType type = state.type();
            if (type.blocksMove()) {
                rooted = true;
            }
            if (type.slow() > 0) {
                speed *= StateRules.speedFactor(type.slow(), state.amount());
            }
        }
        modifier(entity, Attribute.MOVEMENT_SPEED, rooted ? 0 : speed);
        modifier(entity, Attribute.JUMP_STRENGTH, rooted ? 0 : 1);
    }

    private void modifier(LivingEntity entity, Attribute attribute, double factor) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(key);
        if (factor < 1) {
            // Temporaire : jamais écrit dans les données du joueur ou du monstre
            instance.addTransientModifier(new AttributeModifier(key, factor - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    private void tick() {
        ticks += TICK;
        if (holders.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean display = ticks % 20 == 0;
        for (UUID uuid : new ArrayList<>(holders.keySet())) {
            Map<String, StateInstance> states = holders.get(uuid);
            if (states == null) {
                continue;
            }
            Entity found = Bukkit.getEntity(uuid);
            if (!(found instanceof LivingEntity entity) || !entity.isValid()) {
                // Joueur déconnecté : ses états continuent de s'écouler (pas d'esquive par la déconnexion) ; monstre déchargé : oubliés
                if (found == null && Bukkit.getOfflinePlayer(uuid).hasPlayedBefore()) {
                    states.values().removeIf(state -> state.endsAt() <= now);
                    if (states.isEmpty()) {
                        holders.remove(uuid);
                    }
                } else {
                    holders.remove(uuid);
                }
                continue;
            }
            boolean changed = false;
            for (StateInstance state : new ArrayList<>(states.values())) {
                if (state.endsAt() <= now) {
                    states.remove(state.type().id());
                    end(entity, state, StateExpireEvent.Reason.EXPIRED);
                    changed = true;
                    continue;
                }
                StateType type = state.type();
                if (type.damagePerSecond() > 0 && now >= state.nextDamageAt()) {
                    state.nextDamageAt(state.nextDamageAt() + 1000);
                    double amount = type.damagePerSecond() * state.amount() * (moving(entity) ? type.movingFactor() : 1);
                    deal(entity, amount, type.damageKind(), state.source());
                }
                if (type.blocksMove() && entity instanceof Mob mob) {
                    mob.getPathfinder().stopPathfinding();
                }
                if (type.onTick() != null) {
                    type.onTick().accept(entity, state);
                }
            }
            if (changed) {
                refresh(entity);
            }
            if (states.isEmpty()) {
                holders.remove(uuid);
            } else if (display) {
                show(entity, states.values(), now);
            }
        }
        if (display) {
            controlImmuneUntil.values().removeIf(until -> until <= now);
        }
    }

    private static boolean moving(LivingEntity entity) {
        if (entity instanceof Player player) {
            return player.isSprinting();
        }
        var velocity = entity.getVelocity();
        return velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ() > 0.02;
    }

    /** Dégâts d'un état, avec leur auteur ; sans toucher aux frames d'invulnérabilité des vrais coups. */
    private void deal(LivingEntity entity, double amount, fr.eternom.eterLib.module.combat.DamageKind kind, UUID sourceId) {
        if (amount <= 0 || entity.isDead() || combat == null) {
            return;
        }
        Entity source = sourceId == null ? null : Bukkit.getEntity(sourceId);
        dealing = true;
        try {
            combat.damage(source != null && source.isValid() ? source : null, entity, amount, kind);
        } finally {
            dealing = false;
        }
    }

    private void show(LivingEntity entity, Collection<StateInstance> states, long now) {
        if (entity instanceof Player player) {
            if (!actionBar) {
                return;
            }
            StringBuilder line = new StringBuilder();
            for (StateInstance state : states) {
                if (!line.isEmpty()) {
                    line.append("  ");
                }
                line.append(state.type().icon());
                if (state.stacks() > 1) {
                    line.append("<white>").append(state.stacks());
                }
                line.append(" <gray>").append((state.remainingMillis(now) + 999) / 1000).append("s");
            }
            player.sendActionBar(MiniMessage.miniMessage().deserialize(line.toString()));
            return;
        }
        for (StateInstance state : states) {
            if (state.type().particle() != null) {
                entity.getWorld().spawnParticle(state.type().particle(), entity.getLocation().add(0, entity.getHeight() + 0.3, 0),
                        3, 0.25, 0.1, 0.25, 0);
            }
        }
    }

    // ---------- Événements ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (dealing || holders.isEmpty()) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        // Étourdi, apeuré : ses coups ne partent pas (les facteurs de dégâts sont appliqués par le combat)
        if (damager instanceof LivingEntity attacker && !canAttack(attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!holders.isEmpty() && !canAttack(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Étourdi ou apeuré : pas d'arc, de nourriture, de bouclier. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!holders.isEmpty() && event.getAction() != Action.PHYSICAL && !canAttack(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMilk(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.MILK_BUCKET) {
            clear(event.getPlayer(), StateExpireEvent.Reason.PURGED);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (holders.containsKey(event.getEntity().getUniqueId())) {
            clear(event.getEntity(), StateExpireEvent.Reason.DEATH);
        }
    }

    /** Monstre déchargé : ses états sont oubliés (les joueurs gardent les leurs jusqu'à la fin). */
    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            holders.remove(event.getEntity().getUniqueId());
            controlImmuneUntil.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (Attribute attribute : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS}) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(key);
            }
        }
    }
}
