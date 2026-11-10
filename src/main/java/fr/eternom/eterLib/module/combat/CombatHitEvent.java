package fr.eternom.eterLib.module.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Un coup vient d'être calculé par EterLib (après esquive, critique, défense, états et crochets) : pour réagir (classes :
 * « au critique », succès, statistiques). Ne modifie rien ; esquivé : le coup est annulé. Thread principal.
 */
public class CombatHitEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity attacker;
    private final LivingEntity victim;
    private final DamageKind kind;
    private final boolean dodged;
    private final boolean crit;
    private final double damage;

    public CombatHitEvent(LivingEntity attacker, LivingEntity victim, DamageKind kind, boolean dodged, boolean crit, double damage) {
        this.attacker = attacker;
        this.victim = victim;
        this.kind = kind;
        this.dodged = dodged;
        this.crit = crit;
        this.damage = damage;
    }

    /** L'attaquant (le tireur pour un projectile), ou null. */
    public LivingEntity getAttacker() {
        return attacker;
    }

    public LivingEntity getVictim() {
        return victim;
    }

    public DamageKind getKind() {
        return kind;
    }

    public boolean isDodged() {
        return dodged;
    }

    public boolean isCrit() {
        return crit;
    }

    /** Dégâts après défense (avant absorption, bouclier et potion de résistance de Minecraft). */
    public double getDamage() {
        return damage;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
