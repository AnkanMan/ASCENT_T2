package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossRulesTest {

    @Test
    fun `the SPEC example - a 40 hour draft is 4,000 HP and pays 6,000 times the level multiplier`() {
        val tasks = List(8) { BossTaskState(phase = it / 2, hours = 5.0, done = false) }
        assertEquals(4_000, BossRules.maxHp(tasks))
        assertEquals(9_000L, BossRules.defeatXp(4_000, level = 10, enraged = false)) // 6,000 × 1.5
        assertEquals(6_750L, BossRules.defeatXp(4_000, level = 10, enraged = true))
    }

    @Test
    fun `hours are clamped and damage is 100 per hour`() {
        assertEquals(25, BossRules.damage(0.1))
        assertEquals(4_000, BossRules.damage(100.0))
        assertEquals(300, BossRules.damage(3.0))
    }

    @Test
    fun `hp moves only with finished tasks and phases unlock in order`() {
        val tasks = listOf(
            BossTaskState(0, 2.0, done = true),
            BossTaskState(0, 1.0, done = false),
            BossTaskState(1, 3.0, done = false),
        )
        assertEquals(400, BossRules.hp(tasks))
        assertEquals(0, BossRules.currentPhase(tasks, 2))
        assertFalse(BossRules.isUnlocked(tasks[2], tasks, 2))
        val next = tasks.map { if (it.phase == 0) it.copy(done = true) else it }
        assertEquals(1, BossRules.currentPhase(next, 2))
        assertTrue(BossRules.isUnlocked(next[2], next, 2))
        assertFalse(BossRules.isDefeated(next))
        assertTrue(BossRules.isDefeated(next.map { it.copy(done = true) }))
    }

    @Test
    fun `a crit doubles task XP and enrage takes a quarter off`() {
        assertEquals(150L, BossRules.taskXp(2.0, level = 10, critical = false, enraged = false)) // 0.5 × 200 × 1.5
        assertEquals(300L, BossRules.taskXp(2.0, level = 10, critical = true, enraged = false))
        assertEquals(113L, BossRules.taskXp(2.0, level = 10, critical = false, enraged = true))
        assertTrue(BossRules.isEnraged(today = 11, deadline = 10))
        assertFalse(BossRules.isEnraged(today = 10, deadline = 10))
        assertFalse(BossRules.isEnraged(today = 99, deadline = null))
    }

    @Test
    fun `a boss stirs once per idle stretch of five days`() {
        assertFalse(BossRules.stirs(today = 14, lastActivityDay = 10, lastStirDay = null))
        assertTrue(BossRules.stirs(today = 15, lastActivityDay = 10, lastStirDay = null))
        assertFalse(BossRules.stirs(today = 16, lastActivityDay = 10, lastStirDay = 15))
        assertTrue(BossRules.stirs(today = 30, lastActivityDay = 20, lastStirDay = 15))
    }

    @Test
    fun `two bosses plus one raid`() {
        assertTrue(BossRules.canAdd(activeNonRaid = 1, raidActive = false, isRaid = false))
        assertFalse(BossRules.canAdd(activeNonRaid = 2, raidActive = false, isRaid = false))
        assertTrue(BossRules.canAdd(activeNonRaid = 2, raidActive = false, isRaid = true))
        assertFalse(BossRules.canAdd(activeNonRaid = 0, raidActive = true, isRaid = true))
    }

    @Test
    fun `trials beat a record, pay 300 times the level multiplier and wait 4 weeks`() {
        assertTrue(BossRules.trialWon(16.0, 15.0))
        assertFalse(BossRules.trialWon(15.0, 15.0))
        assertEquals(450L, BossRules.trialXp(10))
        assertTrue(BossRules.canAttemptTrial(today = 100, lastAttemptDay = null))
        assertFalse(BossRules.canAttemptTrial(today = 127, lastAttemptDay = 100))
        assertTrue(BossRules.canAttemptTrial(today = 128, lastAttemptDay = 100))
    }
}
