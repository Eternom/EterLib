package fr.eternom.eterLib.module.state;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Un état va être posé (après ténacité et immunités) : annulable, puissance et durée modifiables. Un boss peut l'annuler
 * et poser autre chose à la place (un étourdissement devient un ralentissement). Thread principal.
 */
public class StateApplyEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity target;
    private final StateType type;
    private final Entity source;
    private double power;
    private long durationMillis;
    private boolean cancelled;

    public StateApplyEvent(LivingEntity target, StateType type, Entity source, double power, long durationMillis) {
        this.target = target;
        this.type = type;
        this.source = source;
        this.power = power;
        this.durationMillis = durationMillis;
    }

    public LivingEntity getTarget() {
        return target;
    }

    public StateType getType() {
        return type;
    }

    /** Qui le pose (null : l'environnement, une commande). */
    public Entity getSource() {
        return source;
    }

    public double getPower() {
        return power;
    }

    public void setPower(double power) {
        this.power = power;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = Math.max(0, durationMillis);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
