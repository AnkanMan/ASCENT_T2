package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuestXpTest {

    private fun xp(
        tier: Tier = Tier.NORMAL,
        difficulty: Double = 1.0,
        level: Int = 1,
        streak: Int = 0,
        crit: Boolean = false,
    ) = QuestXp.xp(tier, difficulty, level, streak, crit)

    @Test
    fun `hand-computed cases`() {
        assertEquals(84L, xp(level = 1)) // 80 × 1.05
        assertEquals(120L, xp(level = 10)) // 80 × 1.5
        assertEquals(144L, xp(level = 10, streak = 10)) // 80 × 1.5 × 1.2
        assertEquals(268L, xp(Tier.HARD, difficulty = 1.3, level = 5, streak = 5)) // 268.125
        assertEquals(21L, xp(Tier.TRIVIAL, streak = 1)) // 20 × 1.05 × 1.02 = 21.42
    }

    @Test
    fun `rounds half up`() {
        assertEquals(45L, xp(Tier.EASY, streak = 3)) // 40 × 1.05 × 1.06 = 44.52
        // 20 × 0.95 × 1.5 = 28.5 exactly: half-up gives 29 (banker's rounding would give 28)
        assertEquals(29L, xp(Tier.TRIVIAL, difficulty = 0.95, level = 10))
    }

    @Test
    fun `streak bonus caps at 1_5`() {
        assertEquals(1.5, QuestXp.streakMultiplier(25), 1e-12)
        assertEquals(1.5, QuestXp.streakMultiplier(100), 1e-12)
        assertEquals(180L, xp(level = 10, streak = 25)) // 80 × 1.5 × 1.5
        assertEquals(180L, xp(level = 10, streak = 100))
    }

    @Test
    fun `a raised cap lets the streak bonus go further`() {
        assertEquals(1.6, QuestXp.streakMultiplier(100, cap = 1.6), 1e-12)
    }

    @Test
    fun `critical doubles the XP`() {
        assertEquals(360L, xp(level = 10, streak = 25, crit = true))
    }

    @Test
    fun `difficulty scales linearly`() {
        assertEquals(2 * xp(level = 10), xp(level = 10, difficulty = 2.0))
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { xp(level = 0) }
        assertFailsWith<IllegalArgumentException> { xp(streak = -1) }
        assertFailsWith<IllegalArgumentException> { xp(difficulty = 0.0) }
    }
}
