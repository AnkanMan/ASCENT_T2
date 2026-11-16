package com.tirthankar.ascent.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks 4–5: periods, cooldowns, skill points, unlocks, respec. */
class PeriodsAndSkillsTest {

    private val skills = TestContent.bundle.skills
    private fun clock(h: Int, m: Int) = GameClock(LocalTime.of(h, m), ZoneOffset.UTC)
    private fun day(clock: GameClock, iso: String) = clock.gameDay(Instant.parse(iso))

    @Test
    fun `day, week and month keys follow the reset time`() {
        val at0430 = "2026-11-15T04:30:00Z" // Sunday
        // 04:00 reset: Sunday has begun, a new week.
        assertEquals("w${java.time.LocalDate.of(2026, 11, 15).toEpochDay()}", Periods.weekKey(day(clock(4, 0), at0430)))
        // 05:30 reset: still Saturday's game day, last week.
        assertEquals("w${java.time.LocalDate.of(2026, 11, 8).toEpochDay()}", Periods.weekKey(day(clock(5, 30), at0430)))
        // Month boundary: 1 Dec 03:00 is still 30 Nov's game day.
        assertEquals("m2026-11", Periods.monthKey(day(clock(4, 0), "2026-12-01T03:00:00Z")))
        assertEquals("m2026-12", Periods.monthKey(day(clock(4, 0), "2026-12-01T04:10:00Z")))
        assertEquals(Periods.dayKey(day(clock(5, 30), "2026-11-16T05:00:00Z")), Periods.dayKey(day(clock(5, 30), "2026-11-15T06:00:00Z")))
    }

    @Test
    fun `uses reset at the period boundary`() {
        val c = clock(4, 0)
        val saturday = day(c, "2026-11-14T12:00:00Z")
        val sunday = day(c, "2026-11-15T12:00:00Z")
        val used = Periods.recordUse(LimitPeriod.WEEK, null, saturday, Instant.parse("2026-11-14T12:00:00Z"))
        assertEquals(0, Periods.usesLeft(LimitPeriod.WEEK, 1, used, saturday))
        assertEquals(1, Periods.usesLeft(LimitPeriod.WEEK, 1, used, sunday))
        val again = Periods.recordUse(LimitPeriod.WEEK, used, saturday, Instant.parse("2026-11-14T13:00:00Z"))
        assertEquals(2, again.usesInPeriod)
    }

    @Test
    fun `cooldown reduction - FOC plus Regalia, at most 40 percent, only on hour cooldowns`() {
        val stats = StatBlock(10, 10, 10, 20, 10, 10)
        val regalia = listOf(Effect.CooldownReduction(0.25, "hyperfocus"))
        assertEquals(0.20, Periods.cooldownReduction(stats, emptyList(), "hyperfocus"), 1e-9)
        assertEquals(0.40, Periods.cooldownReduction(stats, regalia, "hyperfocus"), 1e-9)
        assertEquals(0.20, Periods.cooldownReduction(stats, regalia, "insight"), 1e-9)
        assertEquals(Duration.ofMinutes(432), Periods.cooldown(12, 0.40)) // 7.2 h
        val cast = Instant.parse("2026-11-15T10:00:00Z")
        assertTrue(Periods.cooldownLeft(12, 0.40, cast, cast.plus(Duration.ofHours(7))) > Duration.ZERO)
        assertEquals(Duration.ZERO, Periods.cooldownLeft(12, 0.40, cast, cast.plus(Duration.ofMinutes(433))))
        // A per-day limit isn't shortened by reduction: one use is one use.
        assertEquals(1, Periods.usesLeft(LimitPeriod.DAY, 1, null, day(clock(4, 0), "2026-11-15T12:00:00Z")))
    }

    @Test
    fun `points - one per even level plus one per boss, class skills free`() {
        assertEquals(7, SkillRules.pointsEarned(level = 11, bossesDefeated = 2))
        assertEquals(2, SkillRules.pointsSpent(setOf("iron_body", "momentum", "first_principles"), skills))
        assertEquals(5, SkillRules.unspent(11, 2, setOf("iron_body", "momentum"), skills))
    }

    @Test
    fun `unlocks - prerequisites, the higher stat gates the tree, ultimates need a boss`() {
        fun check(id: String, unlocked: Set<String>, stats: StatBlock, bosses: Int = 0, points: Int = 5) =
            SkillRules.canUnlock(skills.skill(id)!!, unlocked, points, stats, bosses, skills)
        val weakStr = StatBlock(str = 12, agi = 21, int = 10, foc = 10, wis = 10, vit = 10)
        assertEquals(UnlockCheck.Ok, check("iron_body", emptySet(), weakStr))
        assertEquals(UnlockCheck.MissingPrerequisite("iron_body"), check("momentum", emptySet(), weakStr))
        // A4: AGI 21 opens Body tier 2 even with STR at 12.
        assertEquals(UnlockCheck.Ok, check("momentum", setOf("iron_body"), weakStr))
        assertEquals(UnlockCheck.GateStat(35, 21), check("limit_break", setOf("iron_body", "momentum"), weakStr))
        val strong = StatBlock(55, 10, 10, 10, 10, 10)
        val tier3 = setOf("iron_body", "momentum", "limit_break", "light_feet", "second_wind", "recovery_aura")
        assertEquals(UnlockCheck.NeedsBoss, check("unbroken", tier3, strong))
        assertEquals(UnlockCheck.Ok, check("unbroken", tier3, strong, bosses = 1))
        assertEquals(UnlockCheck.NoPoints, check("unbroken", tier3, strong, bosses = 1, points = 0))
        assertEquals(UnlockCheck.Locked, check("superposition", emptySet(), strong))
        assertEquals(UnlockCheck.AlreadyUnlocked, check("iron_body", setOf("iron_body"), strong))
    }

    @Test
    fun `respec returns exactly the points spent, a token first, else 50 Essence`() {
        val unlocked = setOf("iron_body", "momentum", "deep_work", "first_principles")
        assertEquals(Respec(3, usesToken = true, essenceCost = 0), SkillRules.respec(unlocked, tokens = 1, essence = 0, content = skills))
        assertEquals(Respec(3, usesToken = false, essenceCost = 50), SkillRules.respec(unlocked, tokens = 0, essence = 60, content = skills))
        assertNull(SkillRules.respec(unlocked, tokens = 0, essence = 49, content = skills))
    }

    @Test
    fun `passive effects come only from unlocked passives`() {
        val effects = SkillRules.passiveEffects(setOf("iron_body", "limit_break", "momentum"), skills)
        assertEquals(listOf(Effect.MasteryMult(Stat.STR, 1.10), Effect.ChainXp(Realm.BODY, 0.03, 0.15, "body")), effects)
    }
}
