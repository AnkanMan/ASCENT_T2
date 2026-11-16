package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverridesTest {

    @Test
    fun `fatigue starts below 30 percent HP`() {
        assertTrue(Fatigue.isFatigued(74, 250))
        assertFalse(Fatigue.isFatigued(75, 250))
    }

    @Test
    fun `re-entry restarts multipliers at 0_8 times, within the clamp`() {
        val out = ReEntry.apply(
            mapOf(
                Realm.BODY to RealmDifficulty(multiplier = 1.0, completionEma = 0.2),
                Realm.MIND to RealmDifficulty(multiplier = 0.55),
            ),
        )
        assertEquals(0.8, out.getValue(Realm.BODY).multiplier, 1e-9)
        assertEquals(0.2, out.getValue(Realm.BODY).completionEma, 1e-9)
        assertEquals(0.5, out.getValue(Realm.MIND).multiplier, 1e-9)
        assertEquals(0.8, out.getValue(Realm.SELF).multiplier, 1e-9)
    }
}
