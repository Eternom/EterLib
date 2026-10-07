package fr.eternom.eterLib.module.server;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Échange de noms avec le proxy (EterTab-Velocity), canal eter:server :
 * - ce serveur donne au proxy son nom affiché (server-display-name), pour qu'EterTab-Velocity n'ait pas à le répéter ;
 * - le proxy donne à ce serveur son vrai nom dans velocity.toml : s'il diffère de server-name, une erreur claire est
 *   écrite dans la console (sinon /home, la présence des joueurs et les téléportations se trompent sans rien dire).
 * Un message Paper <-> proxy passe forcément par un joueur connecté : l'échange a lieu à chaque arrivée.
 */
public class ServerNameListener implements Listener, PluginMessageListener {

    public static final String CHANNEL = "eter:server";

    private final JavaPlugin plugin;
    private final String serverName;
    private final byte[] payload;
    /** Noms erronés déjà signalés : une seule erreur par nom, pas une par joueur. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public ServerNameListener(JavaPlugin plugin, String serverName, String displayName) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.payload = encode(displayName);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
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

    /** Nom de ce serveur dans le proxy, envoyé par EterTab-Velocity. */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        String proxyName;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            proxyName = in.readUTF();
        } catch (IOException e) {
            return; // message mal formé
        }
        if (!proxyName.equals(serverName) && reported.add(proxyName)) {
            plugin.getLogger().severe("""
                    ============================================================
                     server-name vaut "%s" dans EterLib/config.yml, mais le proxy
                     appelle ce serveur "%s" (velocity.toml). Mets server-name: '%s'
                     puis redémarre : sinon /home, les téléportations et la présence
                     des joueurs sur le réseau se trompent de serveur.
                    ============================================================""".formatted(serverName, proxyName, proxyName));
        }
    }

    private void send(Player player) {
        if (player.isOnline() && player.getListeningPluginChannels().contains(CHANNEL)) {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private static byte[] encode(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
