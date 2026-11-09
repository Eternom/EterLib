package fr.eternom.eterLib.module.teleport;

import java.util.function.Consumer;

/**
 * Réglages d'une téléportation, en plus des règles communes (EterLib/config.yml > teleport) :
 * - cancelOnMove : null = la config ; false pour un départ que le joueur n'a pas lancé lui-même (tpa accepté par un
 *   autre : il ne s'y attend pas, bouger ne doit pas l'annuler ; le combat reste vérifié) ;
 * - skipWarmup : départ sans attente (ex : entre membres d'un même clan) ;
 * - onDeparture : le joueur part vraiment ;
 * - onFailed : il ne part pas, et pourquoi (pour rendre une demande de tpa, prévenir l'autre joueur...).
 * Les deux rappels tournent sur le thread principal.
 */
public record TeleportOptions(Boolean cancelOnMove, boolean skipWarmup, Runnable onDeparture, Consumer<Failure> onFailed) {

    /** Pourquoi un départ n'a pas eu lieu. */
    public enum Failure { COMBAT, COOLDOWN, CANCELLED, BUSY, UNREACHABLE }

    public static TeleportOptions defaults() {
        return new TeleportOptions(null, false, () -> { }, failure -> { });
    }

    public TeleportOptions withCancelOnMove(boolean cancel) {
        return new TeleportOptions(cancel, skipWarmup, onDeparture, onFailed);
    }

    public TeleportOptions withoutWarmup() {
        return new TeleportOptions(cancelOnMove, true, onDeparture, onFailed);
    }

    public TeleportOptions onDeparture(Runnable then) {
        return new TeleportOptions(cancelOnMove, skipWarmup, then, onFailed);
    }

    public TeleportOptions onFailed(Consumer<Failure> then) {
        return new TeleportOptions(cancelOnMove, skipWarmup, onDeparture, then);
    }
}
