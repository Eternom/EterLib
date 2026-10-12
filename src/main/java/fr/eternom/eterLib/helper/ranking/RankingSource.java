package fr.eternom.eterLib.helper.ranking;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Une source de classement, offerte par le plugin qui possède les données (ex : les points de succès d'EterSuccess),
 * et lue par celui qui affiche les classements (EterReward), sans que l'un dépende de l'autre : les deux ne dépendent
 * que d'EterLib. Le fournisseur l'enregistre dans le ServicesManager :
 * <pre>
 *     getServer().getServicesManager().register(RankingSource.class, source, this, ServicePriority.Normal);
 * </pre>
 */
public interface RankingSource {

    /** Un joueur et sa valeur dans le classement. */
    record Ranked(UUID player, long value) {
    }

    /** Identifiant du classement (ex : "success"). */
    String id();

    /** Les premiers, du premier au dernier. Bloquant (base). */
    List<Ranked> top(int limit);

    /** {place (1 = premier, 0 si absent), valeur} du joueur. Bloquant (base). */
    long[] of(UUID player);

    /** La source enregistrée sous cet identifiant, si un plugin la fournit sur ce serveur. */
    static Optional<RankingSource> find(String id) {
        return Bukkit.getServicesManager().getRegistrations(RankingSource.class).stream()
                .map(RegisteredServiceProvider::getProvider)
                .filter(source -> source.id().equals(id))
                .findFirst();
    }
}
