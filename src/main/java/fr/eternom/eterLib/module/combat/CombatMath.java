package fr.eternom.eterLib.module.combat;

/**
 * Les calculs du combat, sans Bukkit (testés) : défense effective, réduction, critique, esquive, dégâts finaux.
 */
public final class CombatMath {

    /** Dégâts critiques de base : 1,5 fois. */
    public static final double BASE_CRIT = 1.5;

    /** Résultat d'un coup : esquivé, critique, et dégâts avant absorption, bouclier et potion de résistance. */
    public record Result(boolean dodged, boolean crit, double damage) {
    }

    private CombatMath() {
    }

    /** Réduction (0 à 1) d'une défense : defense / (defense + constant). Jamais 1 : chaque coup passe un peu. */
    public static double reduction(double defense, double constant) {
        double effective = Math.max(0, defense);
        return effective / (effective + Math.max(1, constant));
    }

    /** Défense qui reste après la pénétration (part ignorée, 0 à 0,9). */
    public static double afterPenetration(double defense, double penetration) {
        return Math.max(0, defense) * (1 - Math.clamp(penetration, 0, 0.9));
    }

    /**
     * Un coup complet.
     *
     * @param base          dégâts de base (après les bonus des autres plugins : rage d'un boss...)
     * @param kind          type de dégâts
     * @param bonus         dégâts en plus de l'attaquant pour ce type (part)
     * @param dealtFactor   facteur des états de l'attaquant (faiblesse...)
     * @param critChance    chance de critique de l'attaquant (0 : jamais)
     * @param critDamage    dégâts critiques en plus de 1,5 fois
     * @param defense       armure ou résistance magique de la cible (selon le type)
     * @param penetration   part de cette défense ignorée
     * @param constant      constante de la formule
     * @param takenFactor   facteur des états de la cible (marque...)
     * @param dodge         chance d'esquive de la cible (0 : jamais)
     * @param dodgeRoll     tirage 0 à 1 de l'esquive
     * @param critRoll      tirage 0 à 1 du critique
     */
    public static Result hit(double base, DamageKind kind, double bonus, double dealtFactor, double critChance, double critDamage,
                             double defense, double penetration, double constant, double takenFactor, double dodge,
                             double dodgeRoll, double critRoll) {
        if (kind != DamageKind.TRUE && dodge > 0 && dodgeRoll < dodge) {
            return new Result(true, false, 0);
        }
        double damage = Math.max(0, base) * Math.max(0, 1 + bonus) * Math.max(0, dealtFactor);
        boolean crit = critChance > 0 && critRoll < critChance;
        if (crit) {
            damage *= BASE_CRIT + Math.max(0, critDamage);
        }
        if (kind != DamageKind.TRUE) {
            damage *= 1 - reduction(afterPenetration(defense, penetration), constant);
        }
        return new Result(false, crit, damage * Math.max(0, takenFactor));
    }
}
