package fr.eternom.eterLib.helper.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Aller-retour entre le thread principal (Bukkit) et une tâche de fond (base de données, Redis) :
 * le même schéma partout, avec les erreurs toujours écrites dans la console.
 */
public final class Tasks {

    private Tasks() {
    }

    /** Travail de fond sans réponse (ex : enregistrer une préférence) ; une erreur est seulement écrite dans la console. */
    public static void async(JavaPlugin plugin, Runnable task, String errorContext) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, errorContext, e);
            }
        });
    }

    /**
     * task en tâche de fond, puis then sur le thread principal si le joueur est toujours connecté.
     * En cas d'erreur : écrite dans la console, puis onError sur le thread principal (ex : prévenir le joueur).
     */
    public static <T> void async(JavaPlugin plugin, Player player, Supplier<T> task, Consumer<T> then, Runnable onError) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result;
            try {
                result = task.get();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur en tâche de fond pour " + player.getName(), e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        onError.run();
                    }
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    then.accept(result);
                }
            });
        });
    }
}
