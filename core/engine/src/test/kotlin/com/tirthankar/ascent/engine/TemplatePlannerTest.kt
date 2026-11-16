package com.tirthankar.ascent.engine

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatePlannerTest {

    private val wednesday = LocalDate.of(2026, 11, 11)

    private fun input(
        day: LocalDate = wednesday,
        rest: Boolean = false,
        recovery: Boolean = false,
        fatigued: Boolean = false,
        body: Double = 1.0,
        load: BodyLoad = BodyLoad(),
        memory: Map<String, BodyTargetMemory> = emptyMap(),
        index: Int = 0,
    ) = PlannerInput(
        day = day,
        isRestDay = rest,
        isRecoveryDay = recovery,
        fatigued = fatigued,
        difficulty = mapOf(Realm.BODY to body, Realm.MIND to 1.0, Realm.SELF to 1.0),
        bodyLoad = load,
        baselines = Baselines.DEFAULTS,
        bodyMemory = memory,
        trainingDayIndex = index,
    )

    @Test
    fun `body rotation alternates strength A and B and never repeats strength`() {
        val kinds = (0 until 12).map(TemplatePlanner::bodyKindFor)
        assertEquals(
            listOf(
                TemplateKind.STRENGTH_A, TemplateKind.CARDIO, TemplateKind.STRENGTH_B, TemplateKind.MOBILITY,
                TemplateKind.STRENGTH_A, TemplateKind.CARDIO, TemplateKind.STRENGTH_B, TemplateKind.CARDIO,
                TemplateKind.STRENGTH_A, TemplateKind.MOBILITY, TemplateKind.STRENGTH_B, TemplateKind.CARDIO,
            ),
            kinds,
        )
        val strength = setOf(TemplateKind.STRENGTH_A, TemplateKind.STRENGTH_B)
        kinds.zipWithNext().forEach { (a, b) -> assertTrue(a !in strength || b !in strength, "$a then $b") }
    }

    @Test
    fun `a normal day has 1 Body, 1-2 Mind and 1-2 Self`() {
        (0L until 14L).forEach { offset ->
            val plan = TemplatePlanner.plan(input(day = wednesday.plusDays(offset), index = offset.toInt()))
            val byRealm = plan.quests.groupingBy { it.template.realm }.eachCount()
            assertEquals(1, byRealm[Realm.BODY])
            assertTrue(byRealm.getValue(Realm.MIND) in 1..2)
            assertTrue(byRealm.getValue(Realm.SELF) in 1..2)
            assertTrue(plan.quests.size in 3..5)
            assertTrue(plan.quests.any { it.template.kind == TemplateKind.SLEEP })
        }
    }

    @Test
    fun `rest and recovery days swap Body for recovery and pause the rotation`() {
        listOf(input(rest = true, index = 4), input(recovery = true, index = 4)).forEach {
            val plan = TemplatePlanner.plan(it)
            assertEquals(TemplateKind.BODY_RECOVERY, plan.quests.first { q -> q.template.realm == Realm.BODY }.template.kind)
            assertEquals(4, plan.nextTrainingDayIndex)
        }
    }

    @Test
    fun `fatigue cuts the day to three objectives at 0_7`() {
        val plan = TemplatePlanner.plan(input(fatigued = true))
        assertEquals(3, plan.quests.size)
        assertEquals(TemplateKind.BODY_RECOVERY, plan.quests.first().template.kind)
        val deepWork = plan.quests.first { it.template.kind == TemplateKind.DEEP_WORK }
        assertEquals(45.0, deepWork.target, 1e-9) // 60 × 0.7 = 42 → 45
    }

    @Test
    fun `targets are baseline times d, rounded`() {
        val template = TemplateLibrary["body_push_ups"]!!
        assertEquals(20.0, TemplatePlanner.sizeTarget(template, input(body = 1.0)), 1e-9)
        assertEquals(25.0, TemplatePlanner.sizeTarget(template, input(body = 1.2)), 1e-9) // 24 → 25
        assertEquals(10.0, TemplatePlanner.sizeTarget(template, input(body = 0.5)), 1e-9)
    }

    @Test
    fun `a simulated month keeps every safety rule`() {
        val start = LocalDate.of(2026, 11, 8) // a Sunday
        var memory = emptyMap<String, BodyTargetMemory>()
        var load = BodyLoad()
        var index = 0
        var d = 1.0
        // key → (week start, highest target that week, was a deload week)
        val weekly = mutableMapOf<String, MutableList<Triple<Long, Double, Boolean>>>()
        var yesterdayStrength = false

        for (offset in 0L until 42L) {
            val day = start.plusDays(offset)
            if (offset > 0 && GameClock.isWeekStart(day)) load = SafetyRules.startWeek(load, d)
            d = min(DifficultyController.MAX_MULTIPLIER, d * 1.2) // worst case: d climbs every day
            val rest = day.dayOfWeek == DayOfWeek.SATURDAY
            val plan = TemplatePlanner.plan(input(day = day, rest = rest, body = d, load = load, memory = memory, index = index))
            memory = memory + plan.bodyMemory
            index = plan.nextTrainingDayIndex

            val body = plan.quests.single { it.template.realm == Realm.BODY }
            if (rest) assertEquals(TemplateKind.BODY_RECOVERY, body.template.kind, "rest day $day")
            val strength = body.template.kind == TemplateKind.STRENGTH_A || body.template.kind == TemplateKind.STRENGTH_B
            assertTrue(!(strength && yesterdayStrength), "strength on consecutive days at $day")
            yesterdayStrength = strength

            if (body.template.scales) {
                val week = GameClock.weekStart(day).toEpochDay()
                val list = weekly.getOrPut(body.template.key) { mutableListOf() }
                val last = list.lastOrNull()
                if (last != null && last.first == week) {
                    list[list.lastIndex] = Triple(week, maxOf(last.second, body.target), last.third)
                } else {
                    list += Triple(week, body.target, load.deload)
                }
            }
        }

        assertTrue(weekly.isNotEmpty())
        weekly.forEach { (key, weeks) ->
            weeks.zipWithNext().forEach { (earlier, later) ->
                // Compare with the latest earlier non-deload week.
                val reference = weeks.filter { it.first < later.first && !it.third }.lastOrNull() ?: return@forEach
                assertTrue(
                    later.second <= reference.second * SafetyRules.BODY_WEEKLY_RISE + 1e-9,
                    "$key rose from ${reference.second} to ${later.second} (after ${earlier.second})",
                )
                if (later.third) assertTrue(later.second < reference.second, "$key deload week did not drop")
            }
        }
        assertTrue(load.deload || load.risingWeeks > 0, "the month should have been rising")
    }

    @Test
    fun `the library is complete and consistent`() {
        assertTrue(TemplateLibrary.all.size >= 30)
        assertEquals(TemplateLibrary.all.size, TemplateLibrary.all.map { it.key }.toSet().size, "keys are unique")
        TemplateKind.entries.forEach { kind -> assertTrue(TemplateLibrary.ofKind(kind).isNotEmpty(), "$kind") }
        Realm.entries.forEach { realm -> assertTrue(TemplateLibrary.all.any { it.realm == realm }) }
        TemplateLibrary.all.forEach { template ->
            assertTrue(template.key.startsWith(template.realm.name.lowercase() + "_"), template.key)
            assertTrue(!SafetyRules.isForbidden(template.key.replace('_', ' ')), template.key)
        }
    }
}
