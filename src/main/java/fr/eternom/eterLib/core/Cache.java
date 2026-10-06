package fr.eternom.eterLib.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Cycle de vie du cache Redis : init, getJedis, close.
 * Le cache est optionnel : ne créer helper.cache.RedisCache que si {@link #isEnabled()}.
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
        if (section == null || !section.getBoolean("enabled", false)) {
            plugin.getLogger().info("Cache : désactivé");
            return;
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

        // Échoue dès le démarrage si Redis est injoignable
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
        }

        plugin.getLogger().info("Cache : redis");
    }

    public boolean isEnabled() {
        return jedisPool != null && !jedisPool.isClosed();
    }

    public Jedis getJedis() {
        if (!isEnabled()) {
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
