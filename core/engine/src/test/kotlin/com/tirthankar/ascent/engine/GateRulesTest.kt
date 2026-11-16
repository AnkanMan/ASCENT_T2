package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateRulesTest {

    @Test
    fun `payout is base times level times fraction, the chest only at 100 percent`() {
        assertEquals(2_400L, GateRules.payoutXp(Rank.C, level = 10, fraction = 1.0, isRed = false)) // 1600 × 1.5
        assertEquals(1_200L, GateRules.payoutXp(Rank.C, level = 10, fraction = 0.5, isRed = false))
        assertEquals(1, GateRules.chests(GateRules.outcome(1.0, false), false))
        assertEquals(0, GateRules.chests(GateRules.outcome(0.9, false), false))
        assertEquals(2_640L, GateRules.payoutXp(Rank.C, 10, 1.0, false, rewardMultiplier = 1.1))
    }

    @Test
    fun `below half is a break, above is partial, red gates are all or nothing`() {
        assertEquals(GateOutcome.BROKEN, GateRules.outcome(0.49, isRed = false))
        assertEquals(GateOutcome.PARTIAL, GateRules.outcome(0.5, isRed = false))
        assertEquals(GateOutcome.CLEARED, GateRules.outcome(1.0, isRed = true))
        assertEquals(GateOutcome.RED_FAILED, GateRules.outcome(0.99, isRed = true))
        assertEquals(0L, GateRules.payoutXp(Rank.B, 10, 0.99, isRed = true))
        assertEquals(2, GateRules.chests(GateOutcome.CLEARED, isRed = true))
    }

    @Test
    fun `fraction cleared is the mean of capped progress`() {
        assertEquals(0.75, GateRules.fractionCleared(listOf(10.0 to 10.0, 5.0 to 10.0)), 1e-9)
        assertEquals(1.0, GateRules.fractionCleared(listOf(20.0 to 10.0)), 1e-9)
        assertEquals(0.0, GateRules.fractionCleared(emptyList()), 1e-9)
    }

    @Test
    fun `at E rank the board offers E, E and D`() {
        assertEquals(listOf(Rank.E, Rank.E, Rank.D), GateRules.boardRanks(Rank.E))
        assertEquals(listOf(Rank.B, Rank.A, Rank.S), GateRules.boardRanks(Rank.A))
        assertEquals(listOf(Rank.A, Rank.S, Rank.S), GateRules.boardRanks(Rank.SIGMA))
        assertEquals(Rank.D, GateRules.redRank(Rank.E))
    }

    @Test
    fun `the board uses three realms and gives Body the lowest rank that fits the cap`() {
        repeat(20) { seed ->
            val board = GateRules.board(Rank.E, seed.toLong(), bodyHeadroom = 0.10)
            assertEquals(3, board.size)
            assertEquals(Realm.entries.toSet(), board.map { it.second }.toSet())
            assertEquals(Rank.E, board.first { it.second == Realm.BODY }.first)
        }
    }

    @Test
    fun `a body gate that would break the 10 percent cap is rejected`() {
        assertFalse(GateRules.bodyFits(Rank.D, headroom = 0.10))
        assertTrue(GateRules.bodyFits(Rank.E, headroom = 0.10))
        val board = GateRules.board(Rank.C, seed = 3, bodyHeadroom = 0.10)
        assertTrue(board.none { it.second == Realm.BODY })
        assertEquals(2, board.size)
        // Body already at its cap this week: no room at all.
        val thisWeek = BodyTargetMemory(7, weekCap = 30.0, lastTarget = 30.0)
        assertEquals(0.0, GateRules.bodyHeadroom(thisWeek, currentTarget = 30.0, weekStartDay = 7, unit = TargetUnit.REPS), 1e-9)
        // At the Sunday reset the memory is last week's: the new cap leaves 10% (30 → 33).
        assertEquals(0.10, GateRules.bodyHeadroom(thisWeek, currentTarget = 30.0, weekStartDay = 14, unit = TargetUnit.REPS), 1e-9)
        // A target that already rose to the new cap leaves nothing.
        assertEquals(0.0, GateRules.bodyHeadroom(thisWeek, currentTarget = 33.0, weekStartDay = 14, unit = TargetUnit.REPS), 1e-9)
        assertEquals(0.10, GateRules.bodyHeadroom(null, null, 14, TargetUnit.REPS), 1e-9)
    }

    @Test
    fun `objective targets scale a normal week by days and rank`() {
        // 2 papers a week, 7 days, C rank (+35%) → 2.7 → 3 papers.
        assertEquals(3.0, GateRules.objectiveTarget(2.0, 7, Rank.C, count = true, unit = TargetUnit.OTHER), 1e-9)
        // 40 push-ups × 2 sessions, 7 days, E (+10%) → 88 → 90 reps.
        assertEquals(90.0, GateRules.objectiveTarget(80.0, 7, Rank.E, count = false, unit = TargetUnit.REPS), 1e-9)
        val objective = GateObjectiveTemplate("Push-ups", "reps", listOf("body_push_ups"), sessions = 2)
        assertEquals(80.0, GateRules.normalWeek(objective, engineTarget = 40.0), 1e-9)
    }

    @Test
    fun `claude's gates get the rank closest to their measured effort`() {
        assertEquals(Rank.C, GateRules.correctedRank(GateRules.measuredExtra(target = 4.0, normalWeek = 3.0, days = 7)))
        assertEquals(Rank.E, GateRules.correctedRank(0.0))
        assertEquals(Rank.S, GateRules.correctedRank(1.5))
    }
}
