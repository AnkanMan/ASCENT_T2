package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StartingStatsTest {

    @Test
    fun `shift runs from minus five to plus five`() {
        assertEquals(-5, StartingStats.shift(Measurement(0.0, 40.0)))
        assertEquals(0, StartingStats.shift(Measurement(20.0, 40.0)))
        assertEquals(5, StartingStats.shift(Measurement(40.0, 40.0)))
        assertEquals(5, StartingStats.shift(Measurement(60.0, 40.0)), "beyond the goal still caps at +5")
    }

    @Test
    fun `shift rounds half up`() {
        assertEquals(-2, StartingStats.shift(Measurement(10.0, 40.0))) // −2.5 → −2
        assertEquals(3, StartingStats.shift(Measurement(30.0, 40.0))) // 2.5 → 3
    }

    @Test
    fun `lower is better uses goal over current`() {
        // 1 km in 6:00 with a 5:00 goal: p = 5/6, shift = round(3.33) = 3.
        assertEquals(3, StartingStats.shift(Measurement(6.0, 5.0, lowerIsBetter = true)))
        assertEquals(5, StartingStats.shift(Measurement(4.5, 5.0, lowerIsBetter = true)))
    }

    @Test
    fun `strength is the mean of push-ups and squats`() {
        val stats = StartingStats.compute(
            AwakeningMeasurements(
                pushUps = Measurement(30.0, 30.0), // +5
                squats = Measurement(10.0, 40.0), // −2
            ),
        )
        assertEquals(12, stats.str) // mean 1.5 → 2
    }

    @Test
    fun `every stat maps to its measurement and FOC stays at ten`() {
        val stats = StartingStats.compute(
            AwakeningMeasurements(
                pushUps = Measurement(15.0, 30.0),
                squats = Measurement(20.0, 40.0),
                agility = Measurement(4_000.0, 10_000.0), // p 0.4 → −1
                deepWorkHours = Measurement(0.5, 4.0), // p 0.125 → −3.75 → −4
                pagesPerWeek = Measurement(70.0, 70.0), // +5
                sleepHours = Measurement(7.0, 7.0), // +5
            ),
        )
        assertEquals(StatBlock(str = 10, agi = 9, int = 6, foc = 10, wis = 15, vit = 15), stats)
    }

    @Test
    fun `skipped measurements leave stats at ten`() {
        assertEquals(StatBlock.uniform(10), StartingStats.compute(AwakeningMeasurements()))
    }

    @Test
    fun `invalid measurements are rejected`() {
        assertFailsWith<IllegalArgumentException> { Measurement(-1.0, 10.0) }
        assertFailsWith<IllegalArgumentException> { Measurement(1.0, 0.0) }
    }
}
