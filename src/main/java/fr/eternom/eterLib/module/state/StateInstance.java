package fr.eternom.eterLib.module.state;

import java.util.UUID;

/**
 * Un état posé sur une entité : puissance, piles, fin (millisecondes), et qui l'a posé (null : l'environnement).
 * Modifié seulement sur le thread principal.
 */
public final class StateInstance {

    private final StateType type;
    private double power;
    private int stacks;
    private long endsAt;
    private UUID source;
    private final long appliedAt;
    private long nextDamageAt;

    StateInstance(StateType type, double power, int stacks, long endsAt, UUID source, long now) {
        this.type = type;
        this.power = power;
        this.stacks = stacks;
        this.endsAt = endsAt;
        this.source = source;
        this.appliedAt = now;
        this.nextDamageAt = now + 1000;
    }

    public StateType type() {
        return type;
    }

    public double power() {
        return power;
    }

    public int stacks() {
        return stacks;
    }

    public long endsAt() {
        return endsAt;
    }

    public UUID source() {
        return source;
    }

    public long appliedAt() {
        return appliedAt;
    }

    public double amount() {
        return type.amount(power, stacks);
    }

    public long remainingMillis(long now) {
        return Math.max(0, endsAt - now);
    }

    void set(double power, int stacks, long endsAt, UUID source) {
        this.power = power;
        this.stacks = stacks;
        this.endsAt = endsAt;
        this.source = source;
    }

    long nextDamageAt() {
        return nextDamageAt;
    }

    void nextDamageAt(long at) {
        this.nextDamageAt = at;
    }
}
