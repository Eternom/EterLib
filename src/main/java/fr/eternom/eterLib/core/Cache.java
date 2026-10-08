package fr.eternom.eterLib.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Cycle de vie de Redis : init, getJedis, close. Redis est OBLIGATOIRE sur le réseau Eter (verrous d'EterSync,
 * présence des joueurs, messages entre serveurs) : injoignable au démarrage, EterLib refuse de démarrer.
 */
public class Cache {

    private final JavaPlugin plugin;
    private JedisPool jedisPool;
    private String prefix = "";

    public Cache(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("cache");
        if (section == null) {
            throw new IllegalStateException("Section 'cache' (Redis) absente de EterLib/config.yml : Redis est obligatoire");
        }

        // Reconnexion gérée par le pool : chaque connexion est testée (PING) avant d'être prêtée
        // et les connexions inactives sont vérifiées régulièrement.
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(section.getInt("pool-size", 8));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        String password = section.getString("password", "");
        jedisPool = new JedisPool(poolConfig,
                section.getString("host", "localhost"),
                section.getInt("port", 6379),
                2000,
                password.isEmpty() ? null : password,
                section.getInt("database", 0));
        prefix = section.getString("prefix", "eter:");

        // Échoue dès le démarrage si Redis est injoignable (sans le détail : il peut citer l'adresse et le mot de passe)
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
        } catch (RuntimeException e) {
            jedisPool.close();
            throw new IllegalStateException("Redis injoignable (EterLib/config.yml > cache) : il est obligatoire, EterLib ne démarre pas");
        }

        plugin.getLogger().info("Cache : redis");
    }

    public Jedis getJedis() {
        if (jedisPool == null || jedisPool.isClosed()) {
            throw new IllegalStateException("Le pool Redis n'est pas démarré");
        }
        return jedisPool.getResource();
    }

    public String getPrefix() {
        return prefix;
    }

    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
}
