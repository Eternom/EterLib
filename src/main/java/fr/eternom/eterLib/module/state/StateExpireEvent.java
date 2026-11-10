package fr.eternom.eterLib.module.state;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Un état s'arrête : fini, retiré (commande, capacité), purgé (lait), à la mort, ou monté en un autre état. */
public class StateExpireEvent extends Event {

    public enum Reason { EXPIRED, REMOVED, PURGED, DEATH, ESCALATED }

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity target;
    private final StateInstance state;
    private final Reason reason;

    public StateExpireEvent(LivingEntity target, StateInstance state, Reason reason) {
        this.target = target;
        this.state = state;
        this.reason = reason;
    }

    public LivingEntity getTarget() {
        return target;
    }

    public StateInstance getState() {
        return state;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
