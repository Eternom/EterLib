package fr.eternom.eterLib.module.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Le rôle de combat d'un joueur, pour l'IA des monstres (EterMob) : les futures classes le fournissent (provider) et
 * signalent la menace qui ne passe pas par un coup (soins, boucliers, provocation) ; les monstres l'écoutent (listener).
 * Sans classe : NONE, multiplicateur 1. Depuis EterLib 1.26.0. Thread principal.
 */
public final class CombatRoles {

    public enum Role { NONE, TANK, DAMAGE, HEALER, SUPPORT }

    /** Rôle et multiplicateur de menace (un tank génère plus de menace, un soigneur discret moins). */
    public record Profile(Role role, double threatMultiplier) {
        public static final Profile NONE = new Profile(Role.NONE, 1);
    }

    /** Menace sans coup : player a soigné ou protégé about pour amount (les monstres en combat autour la comptent). */
    public interface ThreatListener {
        void threat(Player player, LivingEntity about, double amount);
    }

    private static final List<Function<Player, Profile>> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<ThreatListener> LISTENERS = new CopyOnWriteArrayList<>();

    private CombatRoles() {
    }

    /** Une classe déclare comment lire le rôle d'un joueur (null : elle ne sait pas). */
    public static void addProvider(Function<Player, Profile> provider) {
        PROVIDERS.add(provider);
    }

    public static void addListener(ThreatListener listener) {
        LISTENERS.add(listener);
    }

    public static Profile of(Player player) {
        for (Function<Player, Profile> provider : PROVIDERS) {
            Profile profile = provider.apply(player);
            if (profile != null) {
                return profile;
            }
        }
        return Profile.NONE;
    }

    /** Signale de la menace : player a soigné ou protégé about (un allié), pour amount. */
    public static void threat(Player player, LivingEntity about, double amount) {
        for (ThreatListener listener : LISTENERS) {
            listener.threat(player, about, amount);
        }
    }
}
