package fr.eternom.eterLib.module.combat;

import java.util.Locale;

/**
 * Statistiques de combat d'une entité, additionnées depuis plusieurs sources (armure de Minecraft convertie, objets,
 * classes, monstres d'EterMob, états). Les parts sont de 0 à 1 (0,1 = 10 %).
 */
public enum Stat {
    /** Réduit les dégâts physiques : réduction = armure / (armure + constante). */
    ARMOR(false),
    /** Réduit les dégâts magiques, même formule. */
    MAGIC_RESIST(false),
    /** Part de l'armure de la cible ignorée. */
    ARMOR_PEN(true),
    /** Part de la résistance magique de la cible ignorée. */
    MAGIC_PEN(true),
    /** Dégâts physiques en plus (part). */
    PHYSICAL_BONUS(true),
    /** Dégâts magiques en plus (part). */
    MAGIC_BONUS(true),
    /** Chance de coup critique. */
    CRIT_CHANCE(true),
    /** Dégâts critiques en plus de la base (1,5 fois) : 0,3 = 1,8 fois. */
    CRIT_DAMAGE(true),
    /** Chance d'esquiver un coup physique ou magique (pas les dégâts bruts ni les explosions). */
    DODGE(true),
    /** Part de la durée des contrôles ignorée (EterLib, états). */
    TENACITY(true);

    private final boolean part;

    Stat(boolean part) {
        this.part = part;
    }

    /** Une part (affichée en %) plutôt qu'un nombre de points. */
    public boolean part() {
        return part;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Stat parse(String text) {
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
