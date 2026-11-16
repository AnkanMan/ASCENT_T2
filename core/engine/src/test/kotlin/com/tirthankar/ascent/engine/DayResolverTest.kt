package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DayResolverTest {

    private fun input(
        vararg done: Pair<Realm, Boolean>,
        rest: Boolean = false,
        hp: Int = 200,
        streak: Int = 12,
        misses: Int = 0,
    ) = DayInput(
        objectives = done.map { ObjectiveResult(it.first, it.second) },
        isRestDay = rest,
        hp = hp,
        maxHp = 250,
        maxMp = 164,
        streakDays = streak,
        consecutiveMisses = misses,
    )

    @Test
    fun `full Daily raises the streak and refills MP`() {
        val out = DayResolver.resolve(input(Realm.BODY to true, Realm.MIND to true, Realm.SELF to true))
        assertTrue(out.dailyComplete)
        assertEquals(13, out.streakDays)
        assertEquals(0, out.hpChange)
        assertEquals(164, out.mp)
        assertEquals(0, out.consecutiveMisses)
    }

    @Test
    fun `full Daily on a rest day restores 30 HP, capped at max`() {
        assertEquals(30, DayResolver.resolve(input(Realm.BODY to true, rest = true, hp = 100)).hpChange)
        val capped = DayResolver.resolve(input(Realm.BODY to true, rest = true, hp = 240))
        assertEquals(250, capped.hp)
        assertEquals(10, capped.hpChange)
    }

    @Test
    fun `a miss resets the streak and costs 15 HP`() {
        val out = DayResolver.resolve(input(Realm.BODY to true, Realm.MIND to false))
        assertFalse(out.dailyComplete)
        assertEquals(0, out.streakDays)
        assertEquals(-15, out.hpChange)
        assertEquals(185, out.hp)
        assertEquals(1, out.consecutiveMisses)
    }

    @Test
    fun `completion is reported per realm`() {
        val out = DayResolver.resolve(
            input(Realm.BODY to true, Realm.MIND to true, Realm.MIND to false, Realm.SELF to false, Realm.SELF to false),
        )
        assertEquals(mapOf(Realm.BODY to 1.0, Realm.MIND to 0.5, Realm.SELF to 0.0), out.completionByRealm)
    }

    @Test
    fun `HP zero makes the next day a recovery day`() {
        val out = DayResolver.resolve(input(Realm.BODY to false, hp = 10))
        assertEquals(0, out.hp)
        assertTrue(out.nextDayIsRecovery)
    }

    @Test
    fun `no Daily changes nothing but MP`() {
        val out = DayResolver.resolve(input(hp = 100))
        assertFalse(out.dailyIssued)
        assertEquals(12, out.streakDays)
        assertEquals(100, out.hp)
        assertEquals(164, out.mp)
        assertTrue(out.completionByRealm.isEmpty())
    }

    @Test
    fun `re-entry triggers once, on the third straight miss`() {
        assertFalse(DayResolver.resolve(input(Realm.BODY to false, misses = 1)).triggersReEntry)
        assertTrue(DayResolver.resolve(input(Realm.BODY to false, misses = 2)).triggersReEntry)
        assertFalse(DayResolver.resolve(input(Realm.BODY to false, misses = 3)).triggersReEntry)
    }
}
