package fr.eternom.eterLib.module.combat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joueurs en combat (contre un joueur ou un monstre) : ils ne peuvent pas se téléporter
 * pendant tagDuration après le dernier coup donné ou reçu. Propre à ce serveur.
 */
public class CombatTracker {

    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();
    private final long tagMillis;

    public CombatTracker(Duration tagDuration) {
        this.tagMillis = tagDuration.toMillis();
    }

    public void tag(UUID player) {
        if (tagMillis > 0) {
            taggedUntil.put(player, System.currentTimeMillis() + tagMillis);
        }
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
