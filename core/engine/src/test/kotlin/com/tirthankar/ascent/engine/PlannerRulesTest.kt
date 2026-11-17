package com.tirthankar.ascent.engine

import kotlin.test.*

class PlannerRulesTest {
    @Test fun `empty and partially done lists never count as complete`() {
        assertFalse(PlannerRules.allDone(emptyList()))
        assertFalse(PlannerRules.allDone(listOf(true, false)))
        assertTrue(PlannerRules.allDone(listOf(true, true)))
        assertEquals(10L, PlannerRules.WRITING_XP)
        assertEquals(40L, PlannerRules.COMPLETION_XP)
    }
    @Test fun `elapsed time uses the same boot and ignores invalid anchors`() {
        assertEquals(17L, PlannerRules.elapsed(10, 1000, 2, 8000, 2))
        assertEquals(10L, PlannerRules.elapsed(10, 1000, 1, 8000, 2))
        assertEquals(10L, PlannerRules.elapsed(10, 9000, 2, 8000, 2))
        assertEquals(10L, PlannerRules.elapsed(10, null, 2, 8000, 2))
    }
    @Test fun `results reject nonfinite and fractional count values`() {
        assertFalse(PlannerRules.validResult(Double.NaN, MeasurementMode.QUANTITY))
        assertFalse(PlannerRules.validResult(Double.POSITIVE_INFINITY, MeasurementMode.DURATION))
        assertFalse(PlannerRules.validResult(-1.0, MeasurementMode.COUNT))
        assertFalse(PlannerRules.validResult(1.5, MeasurementMode.COUNT))
        assertTrue(PlannerRules.validResult(1.5, MeasurementMode.QUANTITY))
        assertTrue(PlannerRules.validResult(0.0, MeasurementMode.COUNT))
        assertEquals(1.5, PlannerRules.quantity(90, TargetUnit.MINUTES))
    }
}
