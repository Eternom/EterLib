package fr.eternom.eterLib.module.state;

/**
 * Les calculs des états, sans Bukkit (testés) : cumul d'une nouvelle pose avec l'état déjà là, durée d'un contrôle selon
 * la ténacité, facteur de vitesse.
 */
public final class StateRules {

    /** Résultat d'une pose sur un état existant : puissance, piles et fin. */
    public record Merge(double power, int stacks, long endsAt) {
    }

    private StateRules() {
    }

    /**
     * Fusionne une nouvelle pose (power, durationMillis) avec l'état actif (null s'il n'y en a pas).
     * REFRESH : la plus forte puissance, la fin la plus tardive ; STACK : une pile de plus (au plus max), la durée remise à
     * neuf, la plus forte puissance ; STRONGEST : une pose plus faible ne fait que prolonger si elle dure plus longtemps,
     * une plus forte remplace.
     */
    public static Merge merge(StateType.Stacking stacking, int maxStacks, double currentPower, int currentStacks, long currentEnd,
                              boolean exists, double power, long durationMillis, long now) {
        long end = now + Math.max(0, durationMillis);
        if (!exists) {
            return new Merge(power, 1, end);
        }
        return switch (stacking) {
            case REFRESH -> new Merge(Math.max(currentPower, power), 1, Math.max(currentEnd, end));
            case STACK -> new Merge(Math.max(currentPower, power), Math.min(Math.max(1, maxStacks), currentStacks + 1), Math.max(currentEnd, end));
            case STRONGEST -> power > currentPower ? new Merge(power, 1, end)
                    : new Merge(currentPower, 1, Math.max(currentEnd, end));
        };
    }

    /** Durée d'un contrôle après ténacité (0 à maxTenacity) et facteur d'immunité (0 = immunisé, 1 = normal). */
    public static long controlDuration(long durationMillis, double tenacity, double maxTenacity, double immunityFactor) {
        double kept = 1 - Math.clamp(tenacity, 0, Math.clamp(maxTenacity, 0, 0.95));
        return Math.max(0, Math.round(durationMillis * kept * Math.clamp(immunityFactor, 0, 1)));
    }

    /** Facteur de vitesse d'un ralentissement de cette quantité (part par unité × quantité), jamais négatif. */
    public static double speedFactor(double slowPerUnit, double amount) {
        return Math.clamp(1 - slowPerUnit * amount, 0, 1);
    }

    /** Facteur d'un bonus ou malus de dégâts (part par unité × quantité), jamais sous 0. */
    public static double damageFactor(double partPerUnit, double amount) {
        return Math.max(0, 1 + partPerUnit * amount);
    }
}
