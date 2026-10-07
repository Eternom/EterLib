package fr.eternom.eterLib.module.player;

import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

/**
 * Tient eter_players et la présence à jour à chaque connexion et déconnexion, et coupe les messages d'arrivée et de
 * départ de Minecraft (vanilla-join-quit-messages) : en réseau, le proxy les annonce une seule fois.
 */
public class PlayerListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerDirectory directory;
    private final boolean vanillaMessages;

    public PlayerListener(JavaPlugin plugin, PlayerDirectory directory, boolean vanillaMessages) {
        this.plugin = plugin;
        this.directory = directory;
        this.vanillaMessages = vanillaMessages;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!vanillaMessages) {
            event.joinMessage(null);
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String locale = player.locale().toString().toLowerCase(Locale.ROOT);
        Tasks.async(plugin, () -> directory.join(uuid, name, locale), "Connexion non enregistrée dans eter_players : " + name);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!vanillaMessages) {
            event.quitMessage(null);
        }
        UUID uuid = event.getPlayer().getUniqueId();
        Tasks.async(plugin, () -> directory.quit(uuid), "Déconnexion non enregistrée dans eter_players : " + uuid);
    }
}
