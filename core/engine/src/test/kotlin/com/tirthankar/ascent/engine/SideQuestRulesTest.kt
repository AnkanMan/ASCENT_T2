package com.tirthankar.ascent.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SideQuestRulesTest {

    @Test
    fun `claude expiry is clamped to 1 to 3 days`() {
        assertEquals(2, SideQuestRules.claudeExpiryDays(null))
        assertEquals(1, SideQuestRules.claudeExpiryDays(0))
        assertEquals(3, SideQuestRules.claudeExpiryDays(9))
        assertEquals(10L, SideQuestRules.lastDay(9, 2))
    }

    @Test
    fun `a balance quest only while fewer than three are visible`() {
        assertTrue(SideQuestRules.shouldIssueBalance(2))
        assertFalse(SideQuestRules.shouldIssueBalance(3))
    }

    @Test
    fun `the balance realm is the lowest completion, empty realms count as zero`() {
        val done = mapOf(Realm.BODY to 7, Realm.MIND to 3, Realm.SELF to 6)
        val total = mapOf(Realm.BODY to 7, Realm.MIND to 7, Realm.SELF to 7)
        assertEquals(Realm.MIND, SideQuestRules.balanceRealm(done, total))
        assertEquals(Realm.SELF, SideQuestRules.balanceRealm(mapOf(Realm.BODY to 1, Realm.MIND to 1), mapOf(Realm.BODY to 1, Realm.MIND to 1)))
    }

    @Test
    fun `body balance quests are never strength work and are recovery on rest days`() {
        repeat(20) { seed ->
            val training = SideQuestRules.balanceTemplate(Realm.BODY, emptySet(), isRestDay = false, seed = seed.toLong())!!
            assertTrue(training.kind == TemplateKind.CARDIO || training.kind == TemplateKind.MOBILITY)
            val rest = SideQuestRules.balanceTemplate(Realm.BODY, emptySet(), isRestDay = true, seed = seed.toLong())!!
            assertEquals(TemplateKind.BODY_RECOVERY, rest.kind)
        }
        val excluded = TemplateLibrary.ofKind(TemplateKind.MIND_OUTPUT).map { it.key }.toSet()
        assertEquals(null, SideQuestRules.balanceTemplate(Realm.MIND, excluded, isRestDay = false, seed = 1))
    }

    @Test
    fun `emergency slots are two per week inside active hours and never on rest days`() {
        val week = LocalDate.of(2026, 11, 15) // a Sunday
        val wake = LocalTime.of(6, 30)
        val quiet = LocalTime.of(22, 30)
        repeat(30) { seed ->
            val slots = EmergencyRules.slots(week, seed.toLong(), setOf(DayOfWeek.SATURDAY), wake, quiet)
            assertEquals(2, slots.size)
            assertEquals(2, slots.map { it.toLocalDate() }.toSet().size)
            slots.forEach { slot ->
                assertTrue(slot.dayOfWeek != DayOfWeek.SATURDAY)
                assertTrue(!slot.toLocalTime().isBefore(wake))
                assertTrue(!slot.toLocalTime().plusHours(EmergencyRules.WINDOW_HOURS).isAfter(quiet))
            }
            assertEquals(slots, EmergencyRules.slots(week, seed.toLong(), setOf(DayOfWeek.SATURDAY), wake, quiet))
        }
    }

    @Test
    fun `no emergency slots when the active day is shorter than the window`() {
        val week = LocalDate.of(2026, 11, 15)
        assertTrue(EmergencyRules.slots(week, 1, emptySet(), LocalTime.of(21, 0), LocalTime.of(22, 30)).isEmpty())
    }

    @Test
    fun `penalty quests rotate by day`() {
        val tasks = TestContent.bundle.quests.penalty
        assertEquals(tasks[0], PenaltyRules.pick(tasks, 0))
        assertEquals(tasks[1], PenaltyRules.pick(tasks, 1))
        assertEquals(null, PenaltyRules.pick(emptyList(), 1))
    }
}
