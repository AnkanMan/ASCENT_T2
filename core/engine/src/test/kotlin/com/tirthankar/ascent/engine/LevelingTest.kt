package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelingTest {

    @Test
    fun `reaching level ten from one grants 27 points and 5 skill points`() {
        val change = Leveling.change(storedLevel = 1, newTotalXp = XpCurve.totalXpToReach(10))
        assertEquals(10, change.toLevel)
        assertEquals(9, change.levelsGained)
        assertEquals(27, change.freePoints)
        assertEquals(5, change.skillPoints) // levels 2, 4, 6, 8, 10
        assertTrue(change.leveledUp)
    }

    @Test
    fun `one XP short of the next level is no level-up`() {
        val change = Leveling.change(9, XpCurve.totalXpToReach(10) - 1)
        assertFalse(change.leveledUp)
        assertEquals(0, change.freePoints)
    }

    @Test
    fun `skill points land on even levels`() {
        assertEquals(1, Leveling.change(9, XpCurve.totalXpToReach(10)).skillPoints)
        assertEquals(0, Leveling.change(10, XpCurve.totalXpToReach(11)).skillPoints)
        assertEquals(1, Leveling.change(11, XpCurve.totalXpToReach(12)).skillPoints)
    }

    @Test
    fun `a level is never lost`() {
        val change = Leveling.change(storedLevel = 10, newTotalXp = 0)
        assertEquals(10, change.toLevel)
        assertFalse(change.leveledUp)
        assertFailsWith<IllegalArgumentException> { LevelChange(10, 9) }
    }
}
