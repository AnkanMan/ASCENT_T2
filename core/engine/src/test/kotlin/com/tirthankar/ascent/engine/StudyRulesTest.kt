package com.tirthankar.ascent.engine

import kotlin.test.*

class StudyRulesTest {
    @Test fun `minimum and linked target are both required`() {
        assertFalse(StudyRules.validDuration(1799))
        assertTrue(StudyRules.validDuration(1800))
        assertFalse(StudyRules.validDuration(1800, 3600))
        assertTrue(StudyRules.validDuration(3600, 3600))
        assertEquals(3, StudyRules.MAX_ATTEMPTS)
    }

    @Test fun `reward boundaries require completion and strictly more than an hour for 100`() {
        assertEquals(0L, StudyRules.reward(1799, 1800))
        assertEquals(0L, StudyRules.reward(3600, 3660))
        assertEquals(0L, StudyRules.reward(600, 600))
        assertEquals(30L, StudyRules.reward(1800, 1800))
        assertEquals(30L, StudyRules.reward(3600, 1800))
        assertEquals(100L, StudyRules.reward(3601, 1800))
        assertEquals(100L, StudyRules.reward(3660, 3660))
    }

    @Test fun `each quote except the last one is reachable without repeats`() {
        for (previous in 0 until StudyRules.QUOTE_COUNT) {
            val choices = (0 until StudyRules.QUOTE_COUNT - 1).map { StudyRules.quote(previous, it) }.toSet()
            assertEquals(StudyRules.QUOTE_COUNT - 1, choices.size)
            assertFalse(previous in choices)
            assertTrue(choices.all { it in 0 until StudyRules.QUOTE_COUNT })
        }
        assertTrue(StudyRules.quote(null, -1) in 0 until StudyRules.QUOTE_COUNT)
    }
}
