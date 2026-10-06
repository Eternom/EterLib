package fr.eternom.eterLib.module.player;

import org.bukkit.Bukkit;
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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> directory.join(uuid, name, locale));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> directory.quit(uuid));
    }
}
