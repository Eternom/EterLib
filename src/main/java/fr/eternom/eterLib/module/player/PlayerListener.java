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

/** Tient eter_players et la présence à jour à chaque connexion et déconnexion. */
public class PlayerListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerDirectory directory;

    public PlayerListener(JavaPlugin plugin, PlayerDirectory directory) {
        this.plugin = plugin;
        this.directory = directory;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String locale = player.locale().toString().toLowerCase(Locale.ROOT);
        Tasks.async(plugin, () -> directory.join(uuid, name, locale), "Connexion non enregistrée dans eter_players : " + name);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Tasks.async(plugin, () -> directory.quit(uuid), "Déconnexion non enregistrée dans eter_players : " + uuid);
    }
}
