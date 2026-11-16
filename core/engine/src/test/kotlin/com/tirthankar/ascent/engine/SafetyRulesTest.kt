package com.tirthankar.ascent.engine

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyRulesTest {

    @Test
    fun `weekly cap uses the friendly step when it stays within 10 percent`() {
        assertEquals(55.0, SafetyRules.weeklyCap(50.0, TargetUnit.REPS), 1e-9)
        assertEquals(8_500.0, SafetyRules.weeklyCap(8_000.0, TargetUnit.STEPS), 1e-9)
    }

    @Test
    fun `weekly cap falls back to a fine step instead of jumping more than 10 percent`() {
        assertEquals(22.0, SafetyRules.weeklyCap(20.0, TargetUnit.REPS), 1e-9) // not 25
        assertEquals(44.0, SafetyRules.weeklyCap(40.0, TargetUnit.REPS), 1e-9) // not 45
        assertEquals(65.0, SafetyRules.weeklyCap(60.0, TargetUnit.SECONDS), 1e-9) // not 75
    }

    @Test
    fun `first issue sets the week's cap`() {
        val (target, memory) = SafetyRules.capBodyTarget(40.0, null, weekStartDay = 0, deload = false, TargetUnit.REPS)
        assertEquals(40.0, target, 1e-9)
        assertEquals(BodyTargetMemory(0, 40.0, 40.0), memory)
    }

    @Test
    fun `a target never rises within a week`() {
        val (target, _) = SafetyRules.capBodyTarget(
            45.0, BodyTargetMemory(0, 40.0, 40.0), weekStartDay = 0, deload = false, TargetUnit.REPS,
        )
        assertEquals(40.0, target, 1e-9)
    }

    @Test
    fun `a new week rises at most 10 percent`() {
        val (target, memory) = SafetyRules.capBodyTarget(
            60.0, BodyTargetMemory(0, 40.0, 40.0), weekStartDay = 7, deload = false, TargetUnit.REPS,
        )
        assertEquals(44.0, target, 1e-9)
        assertEquals(BodyTargetMemory(7, 44.0, 44.0), memory)
    }

    @Test
    fun `targets may always fall`() {
        val (target, _) = SafetyRules.capBodyTarget(
            30.0, BodyTargetMemory(0, 40.0, 40.0), weekStartDay = 7, deload = false, TargetUnit.REPS,
        )
        assertEquals(30.0, target, 1e-9)
    }

    @Test
    fun `deload issues 60 percent and the week after resumes from the pre-deload level`() {
        val before = BodyTargetMemory(0, 40.0, 40.0)
        val (deloaded, duringDeload) = SafetyRules.capBodyTarget(60.0, before, 7, deload = true, TargetUnit.REPS)
        assertEquals(25.0, deloaded, 1e-9) // 0.6 × 40 = 24 → 25
        assertEquals(40.0, duringDeload.lastTarget, 1e-9)
        val (after, _) = SafetyRules.capBodyTarget(60.0, duringDeload, 14, deload = false, TargetUnit.REPS)
        assertEquals(44.0, after, 1e-9)
    }

    @Test
    fun `every fourth rising week is a deload week`() {
        var load = BodyLoad()
        val weeks = listOf(1.05, 1.10, 1.15, 1.20, 1.25).map { d ->
            load = SafetyRules.startWeek(load, d)
            load.deload
        }
        assertEquals(listOf(false, false, false, true, false), weeks)
    }

    @Test
    fun `flat or falling weeks don't count toward a deload`() {
        var load = BodyLoad()
        listOf(1.05, 1.05, 1.0, 1.10, 1.15).forEach { load = SafetyRules.startWeek(load, it) }
        assertEquals(3, load.risingWeeks)
        assertFalse(load.deload)
    }

    @Test
    fun `sleep floor holds at seven hours`() {
        assertEquals(LocalTime.of(22, 30), SafetyRules.latestLightsOut(LocalTime.of(5, 30)))
        assertEquals(LocalTime.of(23, 0), SafetyRules.latestLightsOut(LocalTime.of(6, 0)))
        assertEquals(7.0, SafetyRules.sleepTargetHours(6.0), 1e-9)
        assertEquals(8.0, SafetyRules.sleepTargetHours(8.0), 1e-9)
    }

    @Test
    fun `at least one rest day per week`() {
        assertFalse(SafetyRules.hasEnoughRestDays(emptySet()))
        assertTrue(SafetyRules.hasEnoughRestDays(setOf(DayOfWeek.SUNDAY)))
    }

    @Test
    fun `fasting and calorie quests are forbidden`() {
        listOf(
            "16h fast",
            "Intermittent fasting window 12–8",
            "Stay under 1,800 calories",
            "Track kcal",
            "Skip breakfast",
            "Lose 2 kg this month",
            "OMAD today",
            "No dinner",
            "Fast for 24 hours",
            "Weight loss goal",
        ).forEach { assertTrue(SafetyRules.isForbidden(it), it) }
    }

    @Test
    fun `ordinary quests are allowed`() {
        listOf(
            "Eat breakfast with protein",
            "Cook one real meal",
            "Sprint fast for 30 s",
            "Run 1 km fast",
            "Read 15 pages",
            "Breakfast before 9",
            "Push-ups",
        ).forEach { assertFalse(SafetyRules.isForbidden(it), it) }
    }
}
