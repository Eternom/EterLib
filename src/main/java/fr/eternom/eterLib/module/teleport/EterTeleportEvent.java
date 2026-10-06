package fr.eternom.eterLib.module.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Un joueur part, par une téléportation d'EterLib (home, tpa, rtp, tp du staff...), sur ce serveur ou vers un autre.
 * Lancé sur le thread principal juste avant le départ, une fois les règles passées (combat, délai, attente).
 * Les départs vers un autre serveur ne déclenchent pas le PlayerTeleportEvent de Paper : c'est le seul moyen de tous
 * les voir (ex : /back d'EterEssential retient {@link #getFrom()}).
 */
public class EterTeleportEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location from;
    private final Destination destination;

    public EterTeleportEvent(Player player, Location from, Destination destination) {
        this.player = player;
        this.from = from;
        this.destination = destination;
    }

    public Player getPlayer() {
        return player;
    }

    /** Position quittée (copie). */
    public Location getFrom() {
        return from;
    }

    public Destination getDestination() {
        return destination;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
