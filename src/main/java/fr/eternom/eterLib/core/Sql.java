package fr.eternom.eterLib.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/**
 * Cycle de vie de la connexion MySQL / MariaDB : init du pool, getConnection, close.
 * Un seul pool pour tous les plugins Eter du serveur ; chacun crée son helper.sql.Database avec son préfixe de tables.
 */
public class Sql {

    private final JavaPlugin plugin;
    private HikariDataSource dataSource;
    private boolean mariaDb;

    public Sql(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("database");
        if (section == null) {
            throw new IllegalStateException("Section 'database' manquante dans config.yml");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("Eter-SQL");
        // Le pilote MariaDB se connecte aussi bien à MySQL qu'à MariaDB
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://%s:%d/%s".formatted(
                section.getString("host", "localhost"),
                section.getInt("port", 3306),
                section.getString("name", "eternom")));
        config.setUsername(section.getString("username"));
        config.setPassword(section.getString("password"));
        config.setMaximumPoolSize(section.getInt("pool-size", 10));

        // Reconnexion gérée par Hikari : chaque connexion est validée avant d'être prêtée,
        // les connexions mortes sont remplacées, et elles sont renouvelées avant que la base
        // ne les coupe d'elle-même (wait_timeout MySQL = 8h par défaut).
        config.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
        config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(5));
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));

        dataSource = new HikariDataSource(config);

        // Seule différence de syntaxe entre les deux : l'upsert (voir Database)
        String version = serverVersion();
        mariaDb = version.contains("MariaDB");

        plugin.getLogger().info("Connecté à la base de données (" + version + ")");
    }

    public Connection getConnection() throws SQLException {
        if (!isConnected()) {
            throw new SQLException("Le pool de connexions n'est pas démarré");
        }
        return dataSource.getConnection();
    }

    public boolean isConnected() {
        return dataSource != null && dataSource.isRunning();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /** true si le serveur est MariaDB, false si c'est MySQL. */
    public boolean isMariaDb() {
        return mariaDb;
    }

    private String serverVersion() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT VERSION()")) {
            result.next();
            return result.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible de lire la version du serveur SQL", e);
        }
    }
}
