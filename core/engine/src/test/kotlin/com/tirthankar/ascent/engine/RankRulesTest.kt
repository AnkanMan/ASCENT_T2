package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankRulesTest {

    @Test
    fun `thresholds match SPEC`() {
        assertEquals(
            listOf(1, 10, 25, 45, 70, 100, null),
            Rank.entries.map { it.minLevel },
        )
    }

    @Test
    fun `rankUnlockedBy at each threshold and one level below`() {
        val cases = mapOf(
            1 to Rank.E, 9 to Rank.E,
            10 to Rank.D, 24 to Rank.D,
            25 to Rank.C, 44 to Rank.C,
            45 to Rank.B, 69 to Rank.B,
            70 to Rank.A, 99 to Rank.A,
            100 to Rank.S, 500 to Rank.S,
        )
        cases.forEach { (level, rank) ->
            assertEquals(rank, RankRules.rankUnlockedBy(level), "level $level")
        }
    }

    @Test
    fun `level alone never unlocks sigma`() {
        assertTrue((1..2_000).none { RankRules.rankUnlockedBy(it) == Rank.SIGMA })
        assertNull(RankRules.availableTrial(Rank.S, 10_000))
    }

    @Test
    fun `trial opens only when the next rank's level is reached`() {
        assertNull(RankRules.availableTrial(Rank.E, 9))
        assertEquals(Rank.D, RankRules.availableTrial(Rank.E, 10))
        assertNull(RankRules.availableTrial(Rank.D, 24))
        assertEquals(Rank.C, RankRules.availableTrial(Rank.D, 25))
        assertEquals(Rank.S, RankRules.availableTrial(Rank.A, 100))
    }

    @Test
    fun `trials go one rank at a time`() {
        assertEquals(Rank.D, RankRules.availableTrial(Rank.E, 80))
    }

    @Test
    fun `no trial beyond sigma`() {
        assertNull(RankRules.availableTrial(Rank.SIGMA, 1_000))
    }

    @Test
    fun `invalid level is rejected`() {
        assertFailsWith<IllegalArgumentException> { RankRules.rankUnlockedBy(0) }
        assertFailsWith<IllegalArgumentException> { RankRules.availableTrial(Rank.E, 0) }
    }
}
