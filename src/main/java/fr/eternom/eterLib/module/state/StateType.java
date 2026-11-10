package fr.eternom.eterLib.module.state;

import org.bukkit.Particle;
import fr.eternom.eterLib.module.combat.DamageKind;
import org.bukkit.entity.LivingEntity;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Un état (saignement, étourdissement...) : ce qu'il fait, comment il se cumule et ce qu'il vaut. Les mêmes états servent
 * aux monstres (EterMob) et aux joueurs (futures classes) : une capacité se compare à une autre par la valeur des états
 * qu'elle pose (value).
 * <p>
 * « Quantité » d'un état actif : sa puissance × ses piles (STACK), sinon sa puissance. Chaque effet est donné par unité
 * de quantité : un saignement de puissance 1 à 3 piles fait 3 dégâts par seconde.
 */
public final class StateType {

    /** Contrôle : réduit par la ténacité, suivi d'une courte immunité. */
    public enum Category { CONTROL, DAMAGE, DEBUFF, BUFF }

    /** REFRESH : durée remise à neuf ; STACK : une pile de plus (jusqu'à maxStacks) ; STRONGEST : la plus forte puissance reste. */
    public enum Stacking { REFRESH, STACK, STRONGEST }

    private final String id;
    private final Category category;
    private final Stacking stacking;
    private final int maxStacks;
    private final double weight;
    private final boolean purgeable;
    private final String icon;
    private final Particle particle;
    private final double slow;
    private final boolean blocksMove;
    private final boolean blocksAttack;
    private final boolean blocksAbilities;
    private final double damageDealt;
    private final double damageTaken;
    private final double armor;
    private final double magicResist;
    private final double damagePerSecond;
    private final DamageKind damageKind;
    private final double movingFactor;
    private final String escalateTo;
    private final double escalateSeconds;
    private final BiConsumer<LivingEntity, StateInstance> onTick;

    private StateType(Builder builder) {
        this.id = builder.id;
        this.category = builder.category;
        this.stacking = builder.stacking;
        this.maxStacks = Math.max(1, builder.maxStacks);
        this.weight = builder.weight;
        this.purgeable = builder.purgeable;
        this.icon = builder.icon;
        this.particle = builder.particle;
        this.slow = builder.slow;
        this.blocksMove = builder.blocksMove;
        this.blocksAttack = builder.blocksAttack;
        this.blocksAbilities = builder.blocksAbilities;
        this.damageDealt = builder.damageDealt;
        this.damageTaken = builder.damageTaken;
        this.armor = builder.armor;
        this.magicResist = builder.magicResist;
        this.damagePerSecond = builder.damagePerSecond;
        this.damageKind = builder.damageKind;
        this.movingFactor = builder.movingFactor;
        this.escalateTo = builder.escalateTo;
        this.escalateSeconds = builder.escalateSeconds;
        this.onTick = builder.onTick;
    }

    public static Builder builder(String id, Category category) {
        return new Builder(id, category);
    }

    public String id() {
        return id;
    }

    public Category category() {
        return category;
    }

    public Stacking stacking() {
        return stacking;
    }

    public int maxStacks() {
        return maxStacks;
    }

    /** Valeur d'équilibrage par seconde et par unité de quantité. */
    public double weight() {
        return weight;
    }

    /** Retiré par le lait (et ce qui purge). */
    public boolean purgeable() {
        return purgeable;
    }

    /** Symbole MiniMessage de la barre d'action (« <red>🩸 »). */
    public String icon() {
        return icon;
    }

    /** Particules autour d'un monstre qui l'a, ou null. */
    public Particle particle() {
        return particle;
    }

    /** Part de la vitesse retirée par unité (0,3 : -30 %). */
    public double slow() {
        return slow;
    }

    public boolean blocksMove() {
        return blocksMove;
    }

    public boolean blocksAttack() {
        return blocksAttack;
    }

    /** Pas de capacité (monstres, classes) : à vérifier par celui qui lance la capacité (States#canUseAbilities). */
    public boolean blocksAbilities() {
        return blocksAbilities;
    }

    /** Dégâts infligés : + cette part par unité (négatif : affaibli). */
    public double damageDealt() {
        return damageDealt;
    }

    /** Dégâts reçus : + cette part par unité (marque). */
    public double damageTaken() {
        return damageTaken;
    }

    /** Part de l'armure retirée par unité (fragilité). */
    public double armor() {
        return armor;
    }

    /** Part de la résistance magique retirée par unité (brisure). */
    public double magicResist() {
        return magicResist;
    }

    public double damagePerSecond() {
        return damagePerSecond;
    }

    public DamageKind damageKind() {
        return damageKind;
    }

    /** Dégâts sur la durée × ce facteur si la cible court (saignement). */
    public double movingFactor() {
        return movingFactor;
    }

    /** Au nombre maximal de piles : cet état est posé (gel -> étourdissement) et les piles retirées ; null sinon. */
    public String escalateTo() {
        return escalateTo;
    }

    public double escalateSeconds() {
        return escalateSeconds;
    }

    /** Effet particulier, toutes les 5 ticks (peur, provocation...), ou null. */
    public BiConsumer<LivingEntity, StateInstance> onTick() {
        return onTick;
    }

    /** Quantité d'un état actif. */
    public double amount(double power, int stacks) {
        return stacking == Stacking.STACK ? power * stacks : power;
    }

    /** Valeur d'équilibrage d'une pose : poids × quantité × secondes. Contrôles : la quantité compte pour 1. */
    public double value(double power, double seconds, int stacks) {
        double amount = category == Category.CONTROL ? Math.max(1, stacks) : amount(power, stacks);
        return weight * amount * seconds;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StateType type && type.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public static final class Builder {

        private final String id;
        private final Category category;
        private Stacking stacking = Stacking.REFRESH;
        private int maxStacks = 1;
        private double weight = 1;
        private boolean purgeable;
        private String icon = "<gray>•";
        private Particle particle;
        private double slow;
        private boolean blocksMove;
        private boolean blocksAttack;
        private boolean blocksAbilities;
        private double damageDealt;
        private double damageTaken;
        private double armor;
        private double magicResist;
        private double damagePerSecond;
        private DamageKind damageKind = DamageKind.MAGIC;
        private double movingFactor = 1;
        private String escalateTo;
        private double escalateSeconds;
        private BiConsumer<LivingEntity, StateInstance> onTick;

        private Builder(String id, Category category) {
            this.id = Objects.requireNonNull(id);
            this.category = Objects.requireNonNull(category);
        }

        public Builder stacking(Stacking stacking, int maxStacks) {
            this.stacking = stacking;
            this.maxStacks = maxStacks;
            return this;
        }

        public Builder weight(double weight) {
            this.weight = weight;
            return this;
        }

        public Builder purgeable() {
            this.purgeable = true;
            return this;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Builder particle(Particle particle) {
            this.particle = particle;
            return this;
        }

        public Builder slow(double slow) {
            this.slow = slow;
            return this;
        }

        public Builder blocksMove() {
            this.blocksMove = true;
            return this;
        }

        public Builder blocksAttack() {
            this.blocksAttack = true;
            return this;
        }

        public Builder blocksAbilities() {
            this.blocksAbilities = true;
            return this;
        }

        public Builder damageDealt(double part) {
            this.damageDealt = part;
            return this;
        }

        public Builder damageTaken(double part) {
            this.damageTaken = part;
            return this;
        }

        public Builder armor(double part) {
            this.armor = part;
            return this;
        }

        public Builder magicResist(double part) {
            this.magicResist = part;
            return this;
        }

        public Builder damageOverTime(double perSecond, DamageKind kind, double movingFactor) {
            this.damagePerSecond = perSecond;
            this.damageKind = kind;
            this.movingFactor = movingFactor;
            return this;
        }

        public Builder escalate(String stateId, double seconds) {
            this.escalateTo = stateId;
            this.escalateSeconds = seconds;
            return this;
        }

        public Builder onTick(BiConsumer<LivingEntity, StateInstance> onTick) {
            this.onTick = onTick;
            return this;
        }

        public StateType build() {
            return new StateType(this);
        }
    }
}
