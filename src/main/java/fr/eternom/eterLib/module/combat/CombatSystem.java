package fr.eternom.eterLib.module.combat;

import fr.eternom.eterLib.module.state.States;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Le combat du réseau : types de dégâts (physique, magique, brut), statistiques (armure, résistance magique,
 * pénétrations, bonus, critique, esquive, ténacité) et un seul calcul pour tous les coups entre entités (joueurs, monstres,
 * boss, classes, JcJ). Depuis EterLib 1.27.0.
 * <ul>
 *   <li>L'armure de Minecraft ne réduit plus rien d'elle-même : ses points, sa robustesse et Protection sont convertis
 *   en armure EterLib (combat.vanilla) ; Protection contre les projectiles et contre les explosions en ajoutent contre
 *   ces coups-là. L'équipement de Minecraft ne donne que de l'armure ; les objets custom portent leurs statistiques
 *   (ItemStats).</li>
 *   <li>Calcul : esquive, bonus de l'attaquant, critique, défense après pénétration (défense / (défense + constante)),
 *   états (faiblesse, marque, fragilité, brisure), puis les crochets des plugins (plafond par coup d'EterMob). Le bouclier,
 *   l'absorption et la potion de résistance restent ceux de Minecraft.</li>
 *   <li>Les dégâts de l'environnement (chute, lave, noyade, faim...) restent ceux de Minecraft.</li>
 *   <li>Un plugin inflige des dégâts typés avec damage(source, cible, montant, type) (capacités, états, sorts).</li>
 * </ul>
 */
public final class CombatSystem implements Listener {

    /** Une source de statistiques (EterMob pour ses monstres, les classes...). */
    public interface StatProvider {
        void contribute(LivingEntity entity, StatSheet sheet);
    }

    /** Dernière retouche d'un coup après le calcul (plafond...) : renvoie les dégâts à garder. */
    public interface Hook {
        double apply(LivingEntity attacker, LivingEntity victim, DamageKind kind, double damage);
    }

    /** Dégâts infligés par damage(), en cours. */
    private record Pending(DamageKind kind, Entity source) {
    }

    private final States states;
    private final double constant;
    private final double armorPerPoint;
    private final double toughnessPerPoint;
    private final double protectionPerLevel;
    /** Résistance magique par niveau de Protection (la Protection vanilla réduit aussi la magie). */
    private final double protectionMagicPerLevel;
    private final double specificPerLevel;
    private final double maxDodge;
    private final double maxCrit;
    private final double maxPenetration;
    private final boolean feedback;
    private final List<StatProvider> providers = new CopyOnWriteArrayList<>();
    private final List<Hook> hooks = new CopyOnWriteArrayList<>();
    private Pending pending;

    public CombatSystem(States states, ConfigurationSection config) {
        this.states = states;
        ConfigurationSection vanilla = config == null ? null : config.getConfigurationSection("vanilla");
        ConfigurationSection max = config == null ? null : config.getConfigurationSection("max");
        this.constant = config == null ? 100 : Math.max(1, config.getDouble("defense-constant", 100));
        // Les anciennes valeurs par défaut (6 / 2 / 4 / 6, équipement vanilla très sous-évalué) sont remplacées par les
        // nouvelles, proches du vanilla ; des valeurs changées à la main sont gardées
        boolean oldDefaults = vanilla != null && vanilla.getDouble("armor-per-point") == 6 && vanilla.getDouble("toughness-per-point") == 2
                && vanilla.getDouble("protection-per-level") == 4 && vanilla.getDouble("specific-protection-per-level") == 6;
        if (oldDefaults) {
            org.bukkit.Bukkit.getLogger().info("[EterLib] combat.vanilla : anciennes valeurs par défaut remplacées (armure 9, robustesse 4, Protection 20)");
        }
        ConfigurationSection read = oldDefaults ? null : vanilla;
        this.armorPerPoint = read == null ? 9 : read.getDouble("armor-per-point", 9);
        this.toughnessPerPoint = read == null ? 4 : read.getDouble("toughness-per-point", 4);
        this.protectionPerLevel = read == null ? 20 : read.getDouble("protection-per-level", 20);
        this.specificPerLevel = read == null ? 25 : read.getDouble("specific-protection-per-level", 25);
        this.protectionMagicPerLevel = vanilla == null ? 8 : vanilla.getDouble("protection-magic-per-level", 8);
        this.maxDodge = max == null ? 0.6 : Math.clamp(max.getDouble("dodge", 0.6), 0, 0.95);
        this.maxCrit = max == null ? 1 : Math.clamp(max.getDouble("crit-chance", 1), 0, 1);
        this.maxPenetration = max == null ? 0.9 : Math.clamp(max.getDouble("penetration", 0.9), 0, 0.95);
        this.feedback = config == null || config.getBoolean("feedback", true);
    }

    public void addProvider(StatProvider provider) {
        providers.add(provider);
    }

    public void addHook(Hook hook) {
        hooks.add(hook);
    }

    public double constant() {
        return constant;
    }

    // ---------- Statistiques ----------

    /** Les statistiques d'une entité maintenant, avec leurs sources. */
    public StatSheet stats(LivingEntity entity) {
        StatSheet sheet = new StatSheet();
        AttributeInstance armor = entity.getAttribute(Attribute.ARMOR);
        AttributeInstance toughness = entity.getAttribute(Attribute.ARMOR_TOUGHNESS);
        sheet.add(Stat.ARMOR, (armor == null ? 0 : armor.getValue()) * armorPerPoint, "armure");
        sheet.add(Stat.ARMOR, (toughness == null ? 0 : toughness.getValue()) * toughnessPerPoint, "robustesse");
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            int protection = 0;
            for (ItemStack piece : equipment.getArmorContents()) {
                if (piece != null && !piece.isEmpty()) {
                    protection += piece.getEnchantmentLevel(Enchantment.PROTECTION);
                    ItemStats.read(piece).forEach((stat, value) -> sheet.add(stat, value, "objet : " + name(piece)));
                }
            }
            sheet.add(Stat.ARMOR, protection * protectionPerLevel, "protection");
            sheet.add(Stat.MAGIC_RESIST, protection * protectionMagicPerLevel, "protection");
            ItemStack hand = equipment.getItemInMainHand();
            ItemStats.read(hand).forEach((stat, value) -> sheet.add(stat, value, "en main : " + name(hand)));
        }
        for (StatProvider provider : providers) {
            provider.contribute(entity, sheet);
        }
        // États : part de l'armure et de la résistance magique retirée (fragilité, brisure)
        double armorFactor = states == null ? 1 : states.armorFactor(entity);
        if (armorFactor < 1) {
            sheet.add(Stat.ARMOR, -sheet.get(Stat.ARMOR) * (1 - armorFactor), "état : fragilité");
        }
        double resistFactor = states == null ? 1 : states.magicResistFactor(entity);
        if (resistFactor < 1) {
            sheet.add(Stat.MAGIC_RESIST, -sheet.get(Stat.MAGIC_RESIST) * (1 - resistFactor), "état : brisure");
        }
        return sheet;
    }

    private static String name(ItemStack item) {
        return item.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Armure en plus contre ce coup : Protection contre les projectiles, contre les explosions. */
    private double specific(LivingEntity victim, EntityDamageEvent.DamageCause cause) {
        Enchantment enchantment = switch (cause) {
            case PROJECTILE -> Enchantment.PROJECTILE_PROTECTION;
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> Enchantment.BLAST_PROTECTION;
            default -> null;
        };
        EntityEquipment equipment = victim.getEquipment();
        if (enchantment == null || equipment == null) {
            return 0;
        }
        int levels = 0;
        for (ItemStack piece : equipment.getArmorContents()) {
            if (piece != null && !piece.isEmpty()) {
                levels += piece.getEnchantmentLevel(enchantment);
            }
        }
        return levels * specificPerLevel;
    }

    // ---------- Infliger ----------

    private fr.eternom.eterLib.helper.message.Messages messages;
    private static final org.bukkit.NamespacedKey MAGIC_SEEN = new org.bukkit.NamespacedKey("eterlib", "magic_seen");

    /** Textes d'EterLib, pour expliquer une fois les dégâts magiques à un joueur. */
    public void setMessages(fr.eternom.eterLib.helper.message.Messages messages) {
        this.messages = messages;
    }

    /** Premier dégât magique reçu par un joueur : une fois, ce qui le réduit (la résistance magique, pas l'armure). */
    private void explainMagic(LivingEntity target, DamageKind kind) {
        if (kind != DamageKind.MAGIC || messages == null || !(target instanceof Player player)
                || player.getPersistentDataContainer().has(MAGIC_SEEN, org.bukkit.persistence.PersistentDataType.BYTE)) {
            return;
        }
        player.getPersistentDataContainer().set(MAGIC_SEEN, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        messages.send(player, "combat.magic-first");
    }

    /**
     * Inflige des dégâts typés venant de source (null : sans auteur), par le calcul d'EterLib. Ne touche pas aux frames
     * d'invulnérabilité des vrais coups (plusieurs capacités d'affilée passent toutes).
     */
    public void damage(Entity source, LivingEntity target, double amount, DamageKind kind) {
        if (amount <= 0 || target.isDead()) {
            return;
        }
        explainMagic(target, kind);
        DamageType type = switch (kind) {
            case MAGIC -> source == null ? DamageType.MAGIC : DamageType.INDIRECT_MAGIC;
            case TRUE -> DamageType.GENERIC;
            case PHYSICAL -> source instanceof Player ? DamageType.PLAYER_ATTACK : source instanceof LivingEntity ? DamageType.MOB_ATTACK
                    : DamageType.GENERIC;
        };
        DamageSource.Builder builder = DamageSource.builder(type);
        if (source != null && source.isValid()) {
            builder.withCausingEntity(source).withDirectEntity(source);
        }
        int frames = target.getNoDamageTicks();
        double last = target.getLastDamage();
        Pending previous = pending;
        pending = new Pending(kind, source);
        try {
            target.setNoDamageTicks(0);
            target.damage(amount, builder.build());
        } finally {
            pending = previous;
            if (!target.isDead()) {
                target.setNoDamageTicks(frames);
                target.setLastDamage(last);
            }
        }
    }

    /** Vrai pendant damage() : des dégâts typés d'un plugin, pas un coup normal. */
    public boolean isTypedDamage() {
        return pending != null;
    }

    // ---------- Calcul de chaque coup ----------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    @SuppressWarnings({"deprecation", "removal"})
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Pending typed = pending;
        DamageKind kind;
        LivingEntity attacker = null;
        if (typed != null) {
            kind = typed.kind();
            attacker = typed.source() instanceof LivingEntity living ? living : null;
        } else if (event instanceof EntityDamageByEntityEvent byEntity) {
            kind = kindOf(event.getCause());
            if (kind == null) {
                return;
            }
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter) {
                attacker = shooter;
            } else if (damager instanceof LivingEntity living) {
                attacker = living;
            }
        } else {
            // Environnement (chute, lave, noyade...) : Minecraft
            return;
        }
        StatSheet target = stats(victim);
        StatSheet source = attacker == null ? new StatSheet() : stats(attacker);
        boolean explosion = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        double defense = switch (kind) {
            case PHYSICAL -> target.get(Stat.ARMOR) + specific(victim, event.getCause());
            case MAGIC -> target.get(Stat.MAGIC_RESIST);
            case TRUE -> 0;
        };
        double penetration = Math.min(maxPenetration, kind == DamageKind.MAGIC ? source.get(Stat.MAGIC_PEN) : source.get(Stat.ARMOR_PEN));
        double bonus = kind == DamageKind.MAGIC ? source.get(Stat.MAGIC_BONUS) : kind == DamageKind.PHYSICAL ? source.get(Stat.PHYSICAL_BONUS) : 0;
        double dodge = attacker == null || explosion ? 0 : Math.clamp(target.get(Stat.DODGE), 0, maxDodge);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        CombatMath.Result result = CombatMath.hit(event.getDamage(), kind, bonus,
                attacker == null || states == null ? 1 : states.damageDealtFactor(attacker),
                attacker == null ? 0 : Math.clamp(source.get(Stat.CRIT_CHANCE), 0, maxCrit), source.get(Stat.CRIT_DAMAGE),
                defense, penetration, constant, states == null ? 1 : states.damageTakenFactor(victim), dodge,
                random.nextDouble(), random.nextDouble());
        if (result.dodged()) {
            event.setCancelled(true);
            if (feedback) {
                victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.8f, 1.4f);
                victim.getWorld().spawnParticle(Particle.CLOUD, victim.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.02);
            }
            org.bukkit.Bukkit.getPluginManager().callEvent(new CombatHitEvent(attacker, victim, kind, true, false, 0));
            return;
        }
        double damage = result.damage();
        for (Hook hook : hooks) {
            damage = Math.max(0, hook.apply(attacker, victim, kind, damage));
        }
        event.setDamage(damage);
        // La réduction est déjà faite : l'armure et les enchantements de Minecraft ne retirent plus rien
        for (EntityDamageEvent.DamageModifier modifier : new EntityDamageEvent.DamageModifier[]{
                EntityDamageEvent.DamageModifier.ARMOR, EntityDamageEvent.DamageModifier.MAGIC}) {
            if (event.isApplicable(modifier)) {
                event.setDamage(modifier, 0);
            }
        }
        if (result.crit() && feedback) {
            victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, victim.getHeight() * 0.7, 0), 12, 0.3, 0.3, 0.3, 0.2);
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 1f);
        }
        org.bukkit.Bukkit.getPluginManager().callEvent(new CombatHitEvent(attacker, victim, kind, false, result.crit(), damage));
    }

    /** Coups entre entités de Minecraft : physiques (coups, flèches, explosions), magiques (potions, souffle), bruts (Warden). */
    private static DamageKind kindOf(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, ENTITY_EXPLOSION, BLOCK_EXPLOSION, FALLING_BLOCK, CONTACT -> DamageKind.PHYSICAL;
            case MAGIC, DRAGON_BREATH, POISON, WITHER, LIGHTNING -> DamageKind.MAGIC;
            case SONIC_BOOM -> DamageKind.TRUE;
            default -> null;
        };
    }

    /** Tous les types d'une feuille, pour l'affichage (/eterlib stats). */
    public static Map<Stat, Double> totals(StatSheet sheet) {
        return sheet.totals();
    }
}
