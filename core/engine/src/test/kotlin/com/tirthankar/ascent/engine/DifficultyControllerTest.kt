package com.tirthankar.ascent.engine

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DifficultyControllerTest {

    private fun run(days: Int, completion: Double, start: RealmDifficulty = RealmDifficulty()): List<RealmDifficulty> =
        generateSequence(start) { DifficultyController.update(it, completion) }.take(days + 1).toList()

    @Test
    fun `new realm starts at d 1_0 and c 0_80`() {
        assertEquals(RealmDifficulty(multiplier = 1.0, completionEma = 0.80), RealmDifficulty())
    }

    @Test
    fun `completion on target leaves d unchanged`() {
        val after = run(30, 0.80).last()
        assertEquals(1.0, after.multiplier, 1e-12)
        assertEquals(0.80, after.completionEma, 1e-12)
    }

    @Test
    fun `first perfect day matches the formula`() {
        val day1 = DifficultyController.update(RealmDifficulty(), 1.0)
        assertEquals(0.86, day1.completionEma, 1e-12) // 0.7 × 0.8 + 0.3 × 1
        assertEquals(exp(0.15 * 0.06), day1.multiplier, 1e-12)
    }

    @Test
    fun `sustained perfect days approach plus 3 percent per day`() {
        val days = run(30, 1.0)
        val ratio = days[30].multiplier / days[29].multiplier
        assertEquals(exp(0.03), ratio, 1e-3) // ≈ 1.0305
    }

    @Test
    fun `a bad patch lowers d, faster than perfect days raise it`() {
        // Five zero days from d = 1.0 stays above the 0.5 floor (about 0.69).
        val days = run(5, 0.0)
        assertTrue(days.zipWithNext().all { (a, b) -> b.multiplier < a.multiplier })
        assertTrue(days.last().multiplier > DifficultyController.MIN_MULTIPLIER)
        val drop = 1 - days[5].multiplier / days[4].multiplier
        val rise = exp(0.03) - 1
        assertTrue(drop > rise, "drop $drop should exceed rise $rise")
    }

    @Test
    fun `d stays within 0_5 and 3_0`() {
        assertEquals(3.0, run(400, 1.0).last().multiplier, 1e-12)
        assertEquals(0.5, run(400, 0.0).last().multiplier, 1e-12)
        (run(400, 1.0) + run(400, 0.0)).forEach {
            assertTrue(it.multiplier in 0.5..3.0, "d = ${it.multiplier}")
        }
    }

    @Test
    fun `realms are independent`() {
        val start = Realm.entries.associateWith { RealmDifficulty() }
        val next = DifficultyController.updateAll(start, mapOf(Realm.BODY to 1.0, Realm.MIND to 0.0))
        assertTrue(next.getValue(Realm.BODY).multiplier > 1.0)
        assertTrue(next.getValue(Realm.MIND).multiplier < 1.0)
        assertEquals(RealmDifficulty(), next.getValue(Realm.SELF)) // no objectives today
    }

    @Test
    fun `completion outside 0 to 1 is rejected`() {
        assertFailsWith<IllegalArgumentException> { DifficultyController.update(RealmDifficulty(), 1.1) }
        assertFailsWith<IllegalArgumentException> { DifficultyController.update(RealmDifficulty(), -0.1) }
    }
}
