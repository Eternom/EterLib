package fr.eternom.eterLib.module.combat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joueurs en combat (contre un joueur ou un monstre) : ils ne peuvent pas se téléporter
 * pendant tagDuration après le dernier coup donné ou reçu. Un combat contre un JOUEUR retient aussi l'adversaire, pour
 * la même durée (ex : EterEssential tue sur place celui qui se déconnecte en plein combat). Propre à ce serveur.
 */
public class CombatTracker {

    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Opponent> opponents = new ConcurrentHashMap<>();
    private final long tagMillis;

    public CombatTracker(Duration tagDuration) {
        this.tagMillis = tagDuration.toMillis();
    }

    /** Le dernier joueur affronté et la fin de ce combat. */
    private record Opponent(String name, long until) {
    }

    public void tag(UUID player) {
        if (tagMillis > 0) {
            taggedUntil.put(player, System.currentTimeMillis() + tagMillis);
        }
    }

    /** Un coup entre deux joueurs : chacun en combat, contre l'autre. */
    public void tagPlayers(UUID victim, String victimName, UUID attacker, String attackerName) {
        if (tagMillis <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + tagMillis;
        taggedUntil.put(victim, until);
        taggedUntil.put(attacker, until);
        opponents.put(victim, new Opponent(attackerName, until));
        opponents.put(attacker, new Opponent(victimName, until));
    }

    /** Le joueur affronté si ce combat contre un joueur dure encore ; vide sinon (fini, ou seulement des monstres). */
    public Optional<String> opponent(UUID player) {
        Opponent opponent = opponents.get(player);
        if (opponent == null) {
            return Optional.empty();
        }
        if (opponent.until() < System.currentTimeMillis()) {
            opponents.remove(player);
            return Optional.empty();
        }
        return Optional.of(opponent.name());
    }

    /** Secondes de combat restantes, 0 si le joueur n'est pas en combat. */
    public long remainingSeconds(UUID player) {
        Long until = taggedUntil.get(player);
        if (until == null) {
            return 0;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            taggedUntil.remove(player);
            return 0;
        }
        return (remaining + 999) / 1000;
    }
}
