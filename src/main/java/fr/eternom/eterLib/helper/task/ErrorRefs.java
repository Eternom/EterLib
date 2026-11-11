package fr.eternom.eterLib.helper.task;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Une référence courte (ex : « K3F9 ») pour chaque « Une erreur est survenue » montré à un joueur : il peut la donner au
 * staff, qui la retrouve dans la console. Une tâche de fond qui échoue (Tasks.async) écrit son erreur avec une
 * référence et la retient quelques secondes pour ce joueur ; le message error.generic qui suit reprend la même. Sans
 * erreur retenue, une nouvelle référence est écrite dans la console avec le joueur.
 */
public final class ErrorRefs {

    /** Lettres et chiffres sans ceux qu'on confond (0/O, 1/I/L). */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final long KEEP_MILLIS = 15_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, Recent> RECENT = new ConcurrentHashMap<>();

    private record Recent(String ref, long at) {
    }

    private ErrorRefs() {
    }

    /** Une nouvelle référence de 4 caractères. */
    public static String newRef() {
        char[] out = new char[4];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    /** Une erreur vient d'arriver pour ce joueur : sa référence, retenue pour le message qui va suivre. */
    public static String remember(UUID player) {
        String ref = newRef();
        RECENT.put(player, new Recent(ref, System.currentTimeMillis()));
        return ref;
    }

    /**
     * La référence à montrer à ce destinataire : celle de l'erreur qui vient de lui arriver, sinon une nouvelle (écrite
     * dans la console avec son nom, pour la retrouver).
     */
    public static String forReceiver(CommandSender receiver) {
        if (receiver instanceof Player player) {
            Recent recent = RECENT.remove(player.getUniqueId());
            if (recent != null && System.currentTimeMillis() - recent.at() <= KEEP_MILLIS) {
                return recent.ref();
            }
        }
        String ref = newRef();
        Bukkit.getLogger().warning("[réf. " + ref + "] « erreur générique » montrée à " + receiver.getName());
        return ref;
    }
}
