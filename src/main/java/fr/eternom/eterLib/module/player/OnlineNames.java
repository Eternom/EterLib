package fr.eternom.eterLib.module.player;

import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Pseudos à proposer avec Tab (/msg, /tpa, /pay...) : les joueurs de ce serveur, et ceux des autres serveurs.
 * La liste réseau est relue en tâche de fond toutes les 10 secondes ({@link #refresh}) : Tab ne touche jamais à la base.
 */
public class OnlineNames {

    private final PlayerDirectory directory;
    private volatile List<String> network = List.of();

    public OnlineNames(PlayerDirectory directory) {
        this.directory = directory;
    }

    /** Bloquant (base). */
    public void refresh() {
        network = directory.listOnline().stream().map(NetworkPlayer::name).toList();
    }

    /**
     * Pseudos commençant par start (sans tenir compte des majuscules).
     * @param network false : seulement ce serveur (ex : fonctionnalité qui ne traverse pas les serveurs sans Redis)
     */
    public List<String> complete(String start, boolean network) {
        String prefix = start.toLowerCase(Locale.ROOT);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Stream.concat(Bukkit.getOnlinePlayers().stream().map(Player::getName), network ? this.network.stream() : Stream.empty())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .forEach(names::add);
        return List.copyOf(names);
    }
}
