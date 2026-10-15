package fr.eternom.eterLib.helper.cache;

import fr.eternom.eterLib.core.Cache;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
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
 * Créé par EterLib une fois Redis connecté.
 *
 * Signature (cache.secret, le même partout) : chaque message porte « v1:&lt;signature&gt;:&lt;heure&gt;: » devant lui, une
 * signature HMAC-SHA256 du canal, de l'heure et du texte. À la réception, un message non signé, mal signé ou vieux de
 * plus de 2 minutes est ignoré : quelqu'un qui atteindrait Redis ne peut ni fabriquer un message (argent, sanction,
 * annonce...) ni rejouer un vieux message. Secret vide : messages non signés, comme avant (pour passer un réseau
 * entier à la signature, mettre le même secret partout puis redémarrer).
 */
public class RedisMessenger {

    private static final long RECONNECT_DELAY_MILLIS = 2000;
    private static final String VERSION = "v1:";
    /** Âge maximal d'un message signé (et avance tolérée sur l'horloge des autres serveurs). */
    private static final long MAX_AGE_MILLIS = 120_000;
    /** Un avertissement par minute au plus pour les messages refusés. */
    private static final long WARN_EVERY_MILLIS = 60_000;

    private final Cache cache;
    private final Logger logger;
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    private final byte[] secret; // vide : pas de signature
    private volatile long lastWarning;
    private volatile Listener listener;
    private volatile boolean running = true;
    private Thread thread;

    public RedisMessenger(Cache cache, Logger logger, String secret) {
        this.cache = cache;
        this.logger = logger;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length == 0) {
            logger.warning("Messages entre serveurs NON signés : mettre le même cache.secret sur tous les serveurs du réseau");
        }
    }

    /** Bloquant (Redis) : à appeler hors du thread principal. */
    public void publish(String channel, String message) {
        try (Jedis jedis = cache.getJedis()) {
            String name = channel(channel);
            jedis.publish(name, sign(name, message));
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

    private String sign(String channel, String message) {
        if (secret.length == 0) {
            return message;
        }
        String time = Long.toString(System.currentTimeMillis());
        return VERSION + mac(channel, time, message) + ":" + time + ":" + message;
    }

    /** Le message d'origine, ou null s'il doit être ignoré (non signé, mal signé, trop vieux). */
    private String verify(String channel, String raw) {
        boolean signed = raw.startsWith(VERSION);
        if (secret.length == 0) {
            return signed ? strip(raw) : raw; // un serveur déjà signé, pendant le passage à la signature
        }
        if (!signed) {
            return refuse(channel, "non signé");
        }
        int macEnd = raw.indexOf(':', VERSION.length());
        int timeEnd = macEnd < 0 ? -1 : raw.indexOf(':', macEnd + 1);
        if (timeEnd < 0) {
            return refuse(channel, "illisible");
        }
        String mac = raw.substring(VERSION.length(), macEnd);
        String time = raw.substring(macEnd + 1, timeEnd);
        String message = raw.substring(timeEnd + 1);
        if (!MessageDigest.isEqual(mac.getBytes(StandardCharsets.UTF_8), mac(channel, time, message).getBytes(StandardCharsets.UTF_8))) {
            return refuse(channel, "mauvaise signature");
        }
        try {
            if (Math.abs(System.currentTimeMillis() - Long.parseLong(time)) > MAX_AGE_MILLIS) {
                return refuse(channel, "trop vieux");
            }
        } catch (NumberFormatException e) {
            return refuse(channel, "illisible");
        }
        return message;
    }

    private static String strip(String raw) {
        int macEnd = raw.indexOf(':', VERSION.length());
        int timeEnd = macEnd < 0 ? -1 : raw.indexOf(':', macEnd + 1);
        return timeEnd < 0 ? raw : raw.substring(timeEnd + 1);
    }

    private String refuse(String channel, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > WARN_EVERY_MILLIS) {
            lastWarning = now;
            logger.warning("Message Redis ignoré sur " + channel + " (" + reason + ") : cache.secret différent sur un serveur ?");
        }
        return null;
    }

    private String mac(String channel, String time, String message) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
            hmac.update((channel + "\n" + time + "\n").getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    private String channel(String channel) {
        return cache.getPrefix() + channel;
    }

    private class Listener extends JedisPubSub {

        private final Set<String> confirmed = ConcurrentHashMap.newKeySet();

        @Override
        public void onMessage(String channel, String raw) {
            String message = verify(channel, raw);
            if (message == null) {
                return;
            }
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
