package fr.eternom.eterLib.module.combat;

/**
 * Type de dégâts : physique (réduit par l'armure), magique (réduit par la résistance magique), brut (rien ne le réduit,
 * rare et assumé : saignement, pourriture). Les coups et les flèches de Minecraft sont physiques.
 */
public enum DamageKind {
    PHYSICAL,
    MAGIC,
    TRUE
}
