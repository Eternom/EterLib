package fr.eternom.eterLib.module.server;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Donne au proxy le nom affiché de ce serveur (server-display-name), pour qu'EterTab-Velocity n'ait pas à le répéter
 * dans sa config. Un message Paper -> proxy passe forcément par un joueur connecté : on l'envoie à chaque arrivée,
 * dès que le proxy a déclaré écouter le canal (il le fait juste après la connexion du joueur au serveur).
 */
public class ServerNameListener implements Listener {

    public static final String CHANNEL = "eter:server";

    private final JavaPlugin plugin;
    private final byte[] payload;

    public ServerNameListener(JavaPlugin plugin, String displayName) {
        this.plugin = plugin;
        this.payload = encode(displayName);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    /** Le proxy déclare le canal : c'est le premier moment où le message peut partir. */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent event) {
        if (CHANNEL.equals(event.getChannel())) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> send(player));
        }
    }

    /** Si le canal a été déclaré avant l'arrivée complète du joueur, l'événement ci-dessus a pu être manqué. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(player), 20);
    }

    private void send(Player player) {
        if (player.isOnline() && player.getListeningPluginChannels().contains(CHANNEL)) {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private static byte[] encode(String displayName) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(displayName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
