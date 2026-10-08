package fr.eternom.eterLib.helper.cache;

import fr.eternom.eterLib.core.Cache;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Cache Redis, partagé entre tous les serveurs connectés au même Redis.
 * Toutes les clés sont préfixées (cache.prefix) pour ne pas entrer en conflit avec d'autres plugins.
 * Créé par EterLib une fois Redis connecté.
 */
public class RedisCache {

    private static final String DELETE_IF_VALUE =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final Cache cache;

    public RedisCache(Cache cache) {
        this.cache = cache;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(call(jedis -> jedis.get(key(key))));
    }

    public void set(String key, String value) {
        call(jedis -> jedis.set(key(key), value));
    }

    public void set(String key, String value, Duration ttl) {
        call(jedis -> jedis.psetex(key(key), ttl.toMillis(), value));
    }

    /**
     * Pose la valeur seulement si la clé n'existe pas (verrou) ; elle expire après ttl.
     * @return true si la clé a été posée, false si elle existait déjà
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return "OK".equals(call(jedis -> jedis.set(key(key), value, SetParams.setParams().nx().px(ttl.toMillis()))));
    }

    /**
     * Supprime la clé seulement si elle vaut encore value, en une seule opération atomique
     * (libération d'un verrou sans risquer d'enlever celui qu'un autre serveur vient de prendre).
     */
    public boolean deleteIfValue(String key, String value) {
        Object deleted = call(jedis -> jedis.eval(DELETE_IF_VALUE, List.of(key(key)), List.of(value)));
        return deleted instanceof Long count && count > 0;
    }

    public void delete(String key) {
        call(jedis -> jedis.del(key(key)));
    }

    /** La clé (valeur ou hash) sera supprimée après ttl ; chaque appel repart de zéro. */
    public void expire(String key, Duration ttl) {
        call(jedis -> jedis.pexpire(key(key), ttl.toMillis()));
    }

    public boolean exists(String key) {
        return call(jedis -> jedis.exists(key(key)));
    }

    public Map<String, String> getHash(String key) {
        return call(jedis -> jedis.hgetAll(key(key)));
    }

    public Optional<String> getHashField(String key, String field) {
        return Optional.ofNullable(call(jedis -> jedis.hget(key(key), field)));
    }

    public void setHashField(String key, String field, String value) {
        call(jedis -> jedis.hset(key(key), field, value));
    }

    public void setHash(String key, Map<String, String> values) {
        call(jedis -> {
            jedis.del(key(key));
            return values.isEmpty() ? 0L : jedis.hset(key(key), values);
        });
    }

    public void deleteHashField(String key, String field) {
        call(jedis -> jedis.hdel(key(key), field));
    }

    private String key(String key) {
        return cache.getPrefix() + key;
    }

    /** Emprunte une connexion au pool et la rend automatiquement. */
    private <T> T call(Function<Jedis, T> action) {
        try (Jedis jedis = cache.getJedis()) {
            return action.apply(jedis);
        }
    }
}
