package fr.eternom.eterLib.helper.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Accès à l'économie du serveur (Vault, fourni par EterEconomy), relu à chaque appel : un fournisseur enregistré
 * après le démarrage est vu tout de suite. Sans Vault ou sans fournisseur : economy() vaut null.
 */
public final class Money {

    private Money() {
    }

    public static Economy economy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null; // sans Vault, ne pas toucher à ses classes
        }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    /** Montant au format de l'économie (« 1 250 Heloks »), ou le nombre seul sans économie. */
    public static String format(double amount) {
        Economy economy = economy();
        return economy == null ? String.valueOf(amount) : economy.format(amount);
    }
}
