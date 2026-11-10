package fr.eternom.eterLib.module.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateRulesTest {

    @Test
    void firstApplicationStartsWithOneStack() {
        StateRules.Merge merge = StateRules.merge(StateType.Stacking.STACK, 5, 0, 0, 0, false, 2, 3000, 1000);
        assertEquals(new StateRules.Merge(2, 1, 4000), merge);
    }

    @Test
    void stackAddsAPileUpToTheMaximum() {
        assertEquals(3, StateRules.merge(StateType.Stacking.STACK, 5, 1, 2, 2000, true, 1, 3000, 1000).stacks());
        assertEquals(5, StateRules.merge(StateType.Stacking.STACK, 5, 1, 5, 2000, true, 1, 3000, 1000).stacks());
    }

    @Test
    void strongestKeepsTheStrongerPowerButAWeakerOneCanExtend() {
        StateRules.Merge weaker = StateRules.merge(StateType.Stacking.STRONGEST, 1, 0.5, 1, 2000, true, 0.2, 5000, 1000);
        assertEquals(0.5, weaker.power());
        assertEquals(6000, weaker.endsAt());
        StateRules.Merge stronger = StateRules.merge(StateType.Stacking.STRONGEST, 1, 0.5, 1, 9000, true, 0.8, 1000, 1000);
        assertEquals(0.8, stronger.power());
        assertEquals(2000, stronger.endsAt());
    }

    @Test
    void refreshNeverShortens() {
        assertEquals(9000, StateRules.merge(StateType.Stacking.REFRESH, 1, 1, 1, 9000, true, 1, 1000, 1000).endsAt());
    }

    @Test
    void tenacityIsCappedAndImmunityMultiplies() {
        assertEquals(500, StateRules.controlDuration(1000, 0.5, 0.8, 1));
        assertEquals(200, StateRules.controlDuration(1000, 2, 0.8, 1));
        assertEquals(0, StateRules.controlDuration(1000, 0, 0.8, 0));
        assertEquals(250, StateRules.controlDuration(1000, 0.5, 0.8, 0.5));
    }

    @Test
    void factorsNeverGoNegative() {
        assertEquals(0, StateRules.speedFactor(0.12, 10));
        assertEquals(0.64, StateRules.speedFactor(0.12, 3), 1e-9);
        assertEquals(0, StateRules.damageFactor(-1, 2));
        assertEquals(1.3, StateRules.damageFactor(1, 0.3), 1e-9);
    }
}
