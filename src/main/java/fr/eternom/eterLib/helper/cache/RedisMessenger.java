package fr.eternom.eterLib.helper.cache;

import fr.eternom.eterLib.core.Cache;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Messages entre serveurs (Redis pub/sub) : un serveur publie sur un canal, tous ceux abonnés le reçoivent,
 * y compris lui-même. Les canaux sont préfixés (cache.prefix) comme les clés.
 *
 * Un seul fil d'écoute pour tous les plugins, qui se reconnecte tout seul si Redis tombe ; les messages publiés
 * pendant la coupure sont perdus (pas de file d'attente). Les abonnés sont appelés sur ce fil : repasser sur
 * le thread principal pour toucher au monde ou aux joueurs.
 * À créer seulement si Cache#isEnabled().
 */
public class RedisMessenger {

    private static final long RECONNECT_DELAY_MILLIS = 2000;

    private final Cache cache;
    private final Logger logger;
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    private volatile Listener listener;
    private volatile boolean running = true;
    private Thread thread;

    public RedisMessenger(Cache cache, Logger logger) {
        this.cache = cache;
        this.logger = logger;
    }

    /** Bloquant (Redis) : à appeler hors du thread principal. */
    public void publish(String channel, String message) {
        try (Jedis jedis = cache.getJedis()) {
            jedis.publish(channel(channel), message);
        }
    }

    /** handler est appelé sur le fil d'écoute à chaque message reçu sur channel. */
    public synchronized void subscribe(String channel, Consumer<String> handler) {
        String name = channel(channel);
        handlers.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(handler);
        if (thread == null) {
            thread = Thread.ofPlatform().daemon().name("EterLib-Redis-pubsub").start(this::listen);
            return;
        }
        Listener current = listener;
        if (current != null && current.isSubscribed()) {
            try {
                current.subscribe(name);
            } catch (RuntimeException ignored) {
                // Connexion en cours de coupure : la reconnexion s'abonnera à tous les canaux
            }
        }
    }

    public void close() {
        running = false;
        Listener current = listener;
        if (current != null && current.isSubscribed()) {
            try {
                current.unsubscribe();
            } catch (RuntimeException ignored) {
                // Déjà déconnecté
            }
        }
    }

    private void listen() {
        while (running) {
            try (Jedis jedis = cache.getJedis()) {
                Listener current = new Listener();
                listener = current;
                jedis.subscribe(current, handlers.keySet().toArray(String[]::new)); // bloque jusqu'à la déconnexion
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                logger.warning("Redis pub/sub coupé, nouvelle tentative dans 2 s : " + e.getMessage());
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private String channel(String channel) {
        return cache.getPrefix() + channel;
    }

    private class Listener extends JedisPubSub {

        private final Set<String> confirmed = ConcurrentHashMap.newKeySet();

        @Override
        public void onMessage(String channel, String message) {
            for (Consumer<String> handler : handlers.getOrDefault(channel, List.of())) {
                try {
                    handler.accept(message);
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "Erreur en traitant un message Redis sur " + channel, e);
                }
            }
        }

        /** Un canal ajouté pendant la connexion a pu être manqué : on rattrape à chaque confirmation. */
        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            confirmed.add(channel);
            String[] missing = handlers.keySet().stream().filter(name -> !confirmed.contains(name)).toArray(String[]::new);
            if (missing.length > 0) {
                subscribe(missing);
            }
        }
    }
}
