package fr.eternom.eterLib.helper.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Sidebar temporaire d'un joueur, à la place de la sidebar habituelle (ex : la quête qu'il suit). La sidebar reste
 * dessinée par un seul plugin (EterTab) : les autres plugins déposent ici leur contenu, sans toucher au tableau de
 * scores du joueur (deux plugins qui le changent se remplacent l'un l'autre). Sans EterTab, rien ne s'affiche.
 *
 * Le contenu est une fonction, appelée à chaque rafraîchissement sur le thread principal : il peut donc suivre
 * l'inventaire ou une progression en direct. Le dernier plugin qui appelle show l'emporte ; clear n'efface que le sien.
 */
public final class SidebarOverrides {

    /** Titre et lignes (15 au plus, les suivantes sont ignorées). */
    public record Content(Component title, List<Component> lines) {
    }

    private record Entry(String owner, Function<Player, Content> content) {
    }

    private final Map<UUID, Entry> overrides = new ConcurrentHashMap<>();

    /** owner : nom du plugin, pour ne retirer que sa propre sidebar. content peut renvoyer null (sidebar habituelle). */
    public void show(Player player, String owner, Function<Player, Content> content) {
        overrides.put(player.getUniqueId(), new Entry(owner, content));
    }

    public void clear(UUID player, String owner) {
        overrides.computeIfPresent(player, (uuid, entry) -> entry.owner().equals(owner) ? null : entry);
    }

    /** Thread principal : la sidebar temporaire du joueur, ou null pour la sidebar habituelle. */
    public Content content(Player player) {
        Entry entry = overrides.get(player.getUniqueId());
        return entry == null ? null : entry.content().apply(player);
    }
}
