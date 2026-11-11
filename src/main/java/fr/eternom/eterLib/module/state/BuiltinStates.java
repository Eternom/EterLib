package fr.eternom.eterLib.module.state;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import fr.eternom.eterLib.module.combat.DamageKind;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/**
 * Les états d'EterLib. Puissance : son sens dépend de l'état (dégâts par seconde, part de la vitesse...), rappelé ici.
 * Poids (valeur d'équilibrage par seconde et par unité) : un étourdissement d'1 s vaut 6, un saignement d'1 dégât/s
 * pendant 1 s vaut 1 ; de quoi comparer deux capacités qui posent des états différents.
 * <pre>
 *   bleed     dégâts/s par pile (×1,5 en courant), 5 piles, dégâts physiques (l'armure les réduit)
 *   burn      dégâts/s, la plus forte reste ; éteint dans l'eau ; purgeable
 *   stun      contrôle : ni bouger, ni frapper, ni capacité
 *   root      contrôle : ne bouge plus, frappe encore
 *   slow      part de la vitesse retirée (0,3 = -30 %), la plus forte reste
 *   freeze    -12 % de vitesse par pile, 5 piles : étourdi 1,5 s
 *   fragile   part de l'armure retirée (0,3 = -30 %)
 *   shatter   part de la résistance magique retirée (brisure)
 *   weakness  part des dégâts infligés retirée
 *   mark      part de dégâts reçus en plus
 *   silence   contrôle : pas de capacité
 *   fear      contrôle : ne frappe plus ; un monstre fuit celui qui l'a apeuré
 *   taunt     contrôle : un monstre ne vise plus que celui qui l'a provoqué
 * </pre>
 */
public final class BuiltinStates {

    private BuiltinStates() {
    }

    public static void register(States states) {
        states.register(StateType.builder("bleed", StateType.Category.DAMAGE)
                .stacking(StateType.Stacking.STACK, 5).weight(1).icon("<dark_red>🩸").particle(Particle.DAMAGE_INDICATOR)
                .damageOverTime(1, DamageKind.PHYSICAL, 1.5).build());
        states.register(StateType.builder("burn", StateType.Category.DAMAGE)
                .stacking(StateType.Stacking.STRONGEST, 1).weight(1).purgeable().icon("<gold>🔥").particle(Particle.FLAME)
                .damageOverTime(1, DamageKind.MAGIC, 1)
                .onTick((entity, state) -> {
                    if (entity.isInWater()) {
                        state.set(state.power(), state.stacks(), 0, state.source());
                    }
                }).build());
        states.register(StateType.builder("stun", StateType.Category.CONTROL)
                .weight(6).icon("<yellow>✦").particle(Particle.ENCHANTED_HIT).blocksMove().blocksAttack().blocksAbilities().build());
        states.register(StateType.builder("root", StateType.Category.CONTROL)
                .weight(3).icon("<dark_green>⚓").particle(Particle.MYCELIUM).blocksMove().build());
        states.register(StateType.builder("slow", StateType.Category.DEBUFF)
                .stacking(StateType.Stacking.STRONGEST, 1).weight(3).icon("<gray>»").particle(Particle.CLOUD).slow(1).build());
        states.register(StateType.builder("freeze", StateType.Category.DEBUFF)
                .stacking(StateType.Stacking.STACK, 5).weight(0.5).icon("<aqua>❄").particle(Particle.SNOWFLAKE).slow(0.12)
                .escalate("stun", 1.5)
                .onTick((entity, state) -> entity.setFreezeTicks(Math.max(entity.getFreezeTicks(), Math.min(120, 30 * state.stacks()))))
                .build());
        states.register(StateType.builder("fragile", StateType.Category.DEBUFF)
                .stacking(StateType.Stacking.STRONGEST, 1).weight(4).icon("<red>⛨").particle(Particle.CRIT).armor(1).build());
        states.register(StateType.builder("shatter", StateType.Category.DEBUFF)
                .stacking(StateType.Stacking.STRONGEST, 1).weight(4).icon("<light_purple>◇").particle(Particle.WAX_OFF).magicResist(1).build());
        states.register(StateType.builder("weakness", StateType.Category.DEBUFF)
                .stacking(StateType.Stacking.STRONGEST, 1).weight(4).icon("<dark_gray>⚔").particle(Particle.SMOKE).damageDealt(-1).build());
        states.register(StateType.builder("mark", StateType.Category.DEBUFF)
                .stacking(StateType.Stacking.STRONGEST, 1).weight(4).icon("<gold>◎").particle(Particle.GLOW).damageTaken(1).build());
        states.register(StateType.builder("silence", StateType.Category.CONTROL)
                .weight(2).icon("<light_purple>✖").particle(Particle.NOTE).blocksAbilities().build());
        states.register(StateType.builder("fear", StateType.Category.CONTROL)
                .weight(4).icon("<dark_purple>☠").particle(Particle.SOUL).blocksAttack()
                .onTick(BuiltinStates::flee).build());
        states.register(StateType.builder("taunt", StateType.Category.CONTROL)
                .weight(2).icon("<red>❗").particle(Particle.ANGRY_VILLAGER)
                .onTick((entity, state) -> {
                    if (entity instanceof Mob mob && state.source() != null
                            && Bukkit.getEntity(state.source()) instanceof LivingEntity source && source.isValid()
                            && source.getWorld().equals(mob.getWorld())) {
                        mob.setTarget(source);
                    }
                }).build());
    }

    /** Peur : le monstre s'éloigne de celui qui l'a apeuré (ou de là où il est, sans auteur). */
    private static void flee(LivingEntity entity, StateInstance state) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        mob.setTarget(null);
        Entity source = state.source() == null ? null : Bukkit.getEntity(state.source());
        Location from = source != null && source.getWorld().equals(mob.getWorld()) ? source.getLocation() : mob.getLocation();
        Vector away = mob.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) {
            away = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
        }
        mob.getPathfinder().moveTo(mob.getLocation().add(away.normalize().multiply(6)), 1.2);
    }
}
