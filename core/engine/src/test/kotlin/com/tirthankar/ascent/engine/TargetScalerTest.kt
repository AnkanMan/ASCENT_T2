package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetScalerTest {

    private data class Case(val baseline: Double, val d: Double, val unit: TargetUnit, val expected: Double)

    @Test
    fun `rounds to each unit's step`() {
        listOf(
            Case(40.0, 1.0, TargetUnit.REPS, 40.0),
            Case(40.0, 1.08, TargetUnit.REPS, 45.0), // 43.2 → 45
            Case(40.0, 1.05, TargetUnit.REPS, 40.0), // 42.0 → 40
            Case(90.0, 1.1, TargetUnit.MINUTES, 105.0), // 99 → 105
            Case(90.0, 0.7, TargetUnit.MINUTES, 60.0), // 63 → 60
            Case(8000.0, 1.03, TargetUnit.STEPS, 8000.0), // 8240 → 8000
            Case(8000.0, 1.04, TargetUnit.STEPS, 8500.0), // 8320 → 8500
            Case(15.0, 1.2, TargetUnit.PAGES, 20.0), // 18 → 20
            Case(5.0, 1.1, TargetUnit.KM, 5.5),
            Case(7.0, 1.3, TargetUnit.OTHER, 9.0), // 9.1 → 9
        ).forEach { (baseline, d, unit, expected) ->
            assertEquals(expected, TargetScaler.scaledTarget(baseline, d, unit), 1e-9, "$baseline × $d $unit")
        }
    }

    @Test
    fun `rounds half up`() {
        assertEquals(40.0, TargetScaler.scaledTarget(37.5, 1.0, TargetUnit.REPS), 1e-9) // 7.5 steps → 8
        assertEquals(30.0, TargetScaler.scaledTarget(22.5, 1.0, TargetUnit.MINUTES), 1e-9) // 1.5 steps → 2
    }

    @Test
    fun `small baselines never round to zero`() {
        assertEquals(5.0, TargetScaler.scaledTarget(1.0, 0.5, TargetUnit.REPS), 1e-9)
        assertEquals(15.0, TargetScaler.scaledTarget(5.0, 0.5, TargetUnit.MINUTES), 1e-9)
        assertEquals(500.0, TargetScaler.scaledTarget(100.0, 1.0, TargetUnit.STEPS), 1e-9)
        assertEquals(0.5, TargetScaler.scaledTarget(0.1, 1.0, TargetUnit.KM), 1e-9)
    }

    @Test
    fun `unit labels map to units`() {
        assertEquals(TargetUnit.REPS, TargetUnit.fromLabel("reps"))
        assertEquals(TargetUnit.MINUTES, TargetUnit.fromLabel(" Min "))
        assertEquals(TargetUnit.STEPS, TargetUnit.fromLabel("steps"))
        assertEquals(TargetUnit.PAGES, TargetUnit.fromLabel("pages"))
        assertEquals(TargetUnit.KM, TargetUnit.fromLabel("km"))
        assertEquals(TargetUnit.OTHER, TargetUnit.fromLabel("figures"))
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { TargetScaler.scaledTarget(0.0, 1.0, TargetUnit.REPS) }
        assertFailsWith<IllegalArgumentException> { TargetScaler.scaledTarget(10.0, 0.0, TargetUnit.REPS) }
    }
}
