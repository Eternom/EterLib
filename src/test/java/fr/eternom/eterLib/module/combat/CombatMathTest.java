package fr.eternom.eterLib.module.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatMathTest {

    @Test
    void reductionCurve() {
        assertEquals(0, CombatMath.reduction(0, 100));
        assertEquals(0.5, CombatMath.reduction(100, 100), 1e-9);
        assertEquals(2.0 / 3, CombatMath.reduction(200, 100), 1e-9);
        assertTrue(CombatMath.reduction(10_000, 100) < 1);
        assertEquals(0, CombatMath.reduction(-50, 100));
    }

    @Test
    void penetrationIsCapped() {
        assertEquals(50, CombatMath.afterPenetration(100, 0.5), 1e-9);
        assertEquals(10, CombatMath.afterPenetration(100, 2), 1e-9);
    }

    @Test
    void physicalAgainstArmor() {
        // 10 de base contre 100 d'armure : 5
        CombatMath.Result hit = CombatMath.hit(10, DamageKind.PHYSICAL, 0, 1, 0, 0, 100, 0, 100, 1, 0, 0.5, 0.5);
        assertEquals(5, hit.damage(), 1e-9);
        assertFalse(hit.crit());
    }

    @Test
    void trueDamageIgnoresDefenseAndDodge() {
        CombatMath.Result hit = CombatMath.hit(10, DamageKind.TRUE, 0, 1, 0, 0, 500, 0, 100, 1, 1, 0, 0.5);
        assertFalse(hit.dodged());
        assertEquals(10, hit.damage(), 1e-9);
    }

    @Test
    void critBonusPenetrationAndStates() {
        // 10 × 1,2 (bonus) × 1,8 (critique +0,3) contre 200 de résistance percée à 50 % (100 : -50 %), × 1,2 (marque)
        CombatMath.Result hit = CombatMath.hit(10, DamageKind.MAGIC, 0.2, 1, 1, 0.3, 200, 0.5, 100, 1.2, 0, 0.5, 0.0);
        assertTrue(hit.crit());
        assertEquals(10 * 1.2 * 1.8 * 0.5 * 1.2, hit.damage(), 1e-9);
    }

    @Test
    void dodge() {
        assertTrue(CombatMath.hit(10, DamageKind.PHYSICAL, 0, 1, 0, 0, 0, 0, 100, 1, 0.3, 0.1, 0.5).dodged());
        assertFalse(CombatMath.hit(10, DamageKind.PHYSICAL, 0, 1, 0, 0, 0, 0, 100, 1, 0.3, 0.5, 0.5).dodged());
    }

    @Test
    void weaknessNeverHeals() {
        assertEquals(0, CombatMath.hit(10, DamageKind.PHYSICAL, -2, 1, 0, 0, 0, 0, 100, 1, 0, 0.5, 0.5).damage());
    }
}
