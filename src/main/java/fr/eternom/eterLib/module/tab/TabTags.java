package fr.eternom.eterLib.module.tab;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Étiquettes d'un joueur dans la liste Tab du réseau (dessinée par EterTab-Velocity sur le proxy), canal eter:tab :
 * un plugin Paper pose un texte MiniMessage sous un nom (ex : "job" -> " · Mineur (2)"), le proxy l'affiche à la place
 * de <tag_job> dans tab.player-format. Le proxy garde les étiquettes jusqu'à la déconnexion, même sur un serveur qui
 * ne les pose pas. Seul un texte qui change est renvoyé ; tout est renvoyé quand le joueur arrive sur ce serveur.
 * Thread principal. Le texte n'est jamais une saisie de joueur (il est interprété par MiniMessage sur le proxy).
 */
public class TabTags implements Listener {

    public static final String CHANNEL = "eter:tab";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,32}");

    private final JavaPlugin plugin;
    private final Map<UUID, Map<String, String>> tags = new ConcurrentHashMap<>();

    public TabTags(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    /** name : minuscules, chiffres, _ ; value : MiniMessage (palette commune), vide = retirer. */
    public void set(Player player, String name, String value) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Nom d'étiquette invalide : " + name);
        }
        String text = value == null ? "" : value;
        Map<String, String> own = tags.computeIfAbsent(player.getUniqueId(), uuid -> new ConcurrentHashMap<>());
        if (Objects.equals(own.put(name, text), text)) {
            return; // inchangé
        }
        send(player, name, text);
    }

    public void remove(Player player, String name) {
        set(player, name, "");
    }

    /** Le proxy déclare le canal à l'arrivée du joueur sur ce serveur : on lui renvoie tout. */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent event) {
        if (CHANNEL.equals(event.getChannel())) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> tags.getOrDefault(player.getUniqueId(), Map.of())
                    .forEach((name, value) -> send(player, name, value)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tags.remove(event.getPlayer().getUniqueId());
    }

    private void send(Player player, String name, String value) {
        if (!player.isOnline() || !player.getListeningPluginChannels().contains(CHANNEL)) {
            return; // pas encore déclaré : partira avec onRegister
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(name);
            out.writeUTF(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
    }
}
