package com.tirthankar.ascent.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tasks 7–9: power rules, Shield and Unbroken, passives, classes, rank trials. */
class PowerAndClassTest {

    private val content = TestContent.bundle
    private fun skill(id: String) = content.skills.skill(id)!!
    private val today = LocalDate.of(2026, 11, 18)
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val stats = StatBlock(10, 10, 10, 10, 10, 10)

    private fun check(id: String, mp: Int = 200, use: SkillUse? = null, target: CastTarget = CastTarget(), effects: List<Effect> = emptyList(), s: StatBlock = stats, unlocked: Boolean = true) =
        PowerRules.check(skill(id), unlocked, mp, use, today, now, s, effects, target)

    @Test
    fun `cast checks say which one failed`() {
        assertEquals(CastCheck.NotUnlocked, check("second_wind", unlocked = false))
        assertEquals(CastCheck.NotCastable, check("iron_body"))
        assertEquals(CastCheck.NotEnoughMp(40, 39), check("limit_break", mp = 39))
        assertEquals(CastCheck.RestDay, check("second_wind", target = CastTarget(isRestDay = true)))
        assertEquals(CastCheck.NoHeadroom, check("limit_break", target = CastTarget(limitBreakTarget = null)))
        assertEquals(CastCheck.Ok, check("limit_break", target = CastTarget(limitBreakTarget = 33.0)))
        assertEquals(CastCheck.NeedsTarget, check("eigenstate_collapse", target = CastTarget(chosen = false)))
        assertEquals(CastCheck.NoMissToRestore, check("unbroken"))
        assertEquals(CastCheck.Ok, check("unbroken", target = CastTarget(unshieldedMissYesterday = true)))
        val usedThisWeek = Periods.recordUse(LimitPeriod.WEEK, null, today, now)
        assertEquals(CastCheck.LimitReached(LimitPeriod.WEEK), check("limit_break", use = usedThisWeek, target = CastTarget(limitBreakTarget = 33.0)))
        val justCast = SkillUse(null, 1, now.minus(Duration.ofHours(2)))
        assertTrue(check("hyperfocus", use = justCast) is CastCheck.OnCooldown)
        assertEquals(CastCheck.Ok, check("hyperfocus", use = SkillUse(null, 1, now.minus(Duration.ofHours(13)))))
    }

    @Test
    fun `insight charges scale with WIS and gear, free charges are spent first`() {
        val wis45 = StatBlock(10, 10, 10, 10, 45, 10)
        val lens = listOf(Effect.InsightCharges(1))
        assertEquals(3, PowerRules.allowedUses(skill("insight"), wis45, emptyList()))
        assertEquals(4, PowerRules.allowedUses(skill("insight"), wis45, lens))
        val blade = listOf(Effect.InsightCharges(1, free = true))
        assertEquals(0, PowerRules.mpCost(skill("insight"), null, today, blade))
        val once = Periods.recordUse(LimitPeriod.DAY, null, today, now)
        assertEquals(20, PowerRules.mpCost(skill("insight"), once, today, blade))
        val three = SkillUse(Periods.dayKey(today), 3, now)
        assertEquals(CastCheck.LimitReached(LimitPeriod.DAY), check("insight", use = three, s = wis45))
        assertEquals(CastCheck.Ok, check("insight", use = three, s = wis45, effects = lens))
    }

    @Test
    fun `stillness and long walk trigger by themselves`() {
        assertTrue("stillness" !in content.skills.skills.map { it.id }) // a class skill
        assertEquals(CastCheck.NotCastable, PowerRules.check(content.classes.byId("ascetic")!!.unique, true, 100, null, today, now, stats, emptyList(), CastTarget()))
        assertEquals(30, PowerRules.stillnessMp(120))
        assertTrue(PowerRules.longWalkApplies(setOf("walk"), TargetUnit.MINUTES, 30.0))
        assertFalse(PowerRules.longWalkApplies(setOf("walk"), TargetUnit.MINUTES, 25.0))
    }

    @Test
    fun `shield first, then the monthly extra, then nothing - two misses in a week`() {
        val shield = listOf(Effect.StreakShield(perWeek = 1))
        assertEquals(ShieldDecision(true), ShieldRules.resolveMiss(0, 0, shield))
        assertEquals(ShieldDecision(false), ShieldRules.resolveMiss(1, 0, shield))
        val withLattice = shield + Effect.StreakShield(perMonth = 1)
        assertEquals(ShieldDecision(true, fromMonthly = true), ShieldRules.resolveMiss(1, 0, withLattice))
        assertEquals(ShieldDecision(false), ShieldRules.resolveMiss(1, 1, withLattice))
        assertEquals(ShieldDecision(false), ShieldRules.resolveMiss(0, 0, emptyList()))
        // Unbroken: only the day after an unshielded miss.
        assertTrue(ShieldRules.unbrokenEligible(missDay = 99, today = 100, shielded = false))
        assertFalse(ShieldRules.unbrokenEligible(missDay = 99, today = 100, shielded = true))
        assertFalse(ShieldRules.unbrokenEligible(missDay = 98, today = 100, shielded = false))
        assertEquals(150L, PowerRules.unbrokenXp(300))
    }

    @Test
    fun `hyperfocus pays its bonus only for the full 90 minutes`() {
        assertEquals(HyperfocusRules.Result(90, 2.0, 2.0, full = true), HyperfocusRules.result(90))
        assertEquals(HyperfocusRules.Result(45, 1.0, 1.0, full = false), HyperfocusRules.result(45))
    }

    @Test
    fun `clarity turns the third line into up to three quests`() {
        assertEquals(listOf("Email Prof. K", "fix the KPZ plot", "read 10 pages"), ClarityRules.questTitles("Email Prof. K; fix the KPZ plot and read 10 pages, call home."))
        assertEquals(listOf("Walk"), ClarityRules.questTitles("Walk; skip dinner"))
    }

    @Test
    fun `passives - chains with neutral rest days, caps, dual mastery, bonds, recovery aura`() {
        // Days 1, 2, (3 rest), 4, 5 qualifying: chain 4 at day 5; day 6 not yet done → still 4.
        val q = setOf(1L, 2L, 4L, 5L)
        assertEquals(4, PassiveRules.chainLength(q, setOf(3L), 5))
        assertEquals(4, PassiveRules.chainLength(q, setOf(3L), 6))
        assertEquals(2, PassiveRules.chainLength(q, emptySet(), 5)) // without the rest day, day 3 breaks it
        assertEquals(0, PassiveRules.chainLength(q, emptySet(), 8))
        val momentum = listOf(Effect.ChainXp(Realm.BODY, 0.03, 0.15, "body"))
        assertEquals(0.12, PassiveRules.chainBonus(Realm.BODY, momentum, mapOf("body" to 4)), 1e-9)
        assertEquals(0.15, PassiveRules.chainBonus(Realm.BODY, momentum, mapOf("body" to 30)), 1e-9)
        assertEquals(0.0, PassiveRules.chainBonus(Realm.MIND, momentum, mapOf("body" to 30)), 1e-9)
        val sense = listOf(Effect.DualMastery("paper", listOf(Stat.INT, Stat.WIS)))
        assertEquals(listOf(Stat.INT, Stat.WIS), PassiveRules.masteryStats(Stat.INT, setOf("paper"), sense))
        assertEquals(listOf(Stat.INT), PassiveRules.masteryStats(Stat.INT, emptySet(), sense))
        assertEquals(10, PassiveRules.hpOnComplete(setOf("social"), listOf(Effect.TagHp("social", 10))))
        assertEquals(45, PassiveRules.restDayHp(30, listOf(Effect.RestHpMult(1.5))))
        assertEquals(0.15, PassiveRules.resonanceBonus(listOf(Effect.Resonance(0.05, 0.15)), 3), 1e-9)
    }

    @Test
    fun `classes - scores, ties side by side, Polymath, evolutions`() {
        val classes = content.classes
        val s = StatBlock(str = 12, agi = 11, int = 18, foc = 13, wis = 16, vit = 9)
        val choices = ClassRules.choices(s, classes)
        // Theorist 34, Computationalist 31 are the two top scores.
        assertEquals(listOf("theorist", "computationalist"), choices.map { it.classId })
        val tied = StatBlock(str = 10, agi = 10, int = 15, foc = 12, wis = 12, vit = 12)
        // INT+WIS 27, INT+FOC 27, then FOC+VIT 24 and AGI+WIS 22.
        assertEquals(setOf("theorist", "computationalist", "ascetic"), ClassRules.choices(tied, classes).map { it.classId }.toSet())
        val even = StatBlock(11, 10, 11, 10, 11, 10)
        assertTrue(ClassRules.polymathEligible(even, classes))
        assertTrue(ClassRules.choices(even, classes).any { it.classId == "polymath" })
        assertFalse(ClassRules.polymathEligible(s, classes))
        assertTrue(ClassRules.awakeningDue(10, null, classes))
        assertFalse(ClassRules.awakeningDue(10, "theorist", classes))
        val theorist = classes.byId("theorist")!!
        assertEquals("Theorist", ClassRules.displayName(theorist, Rank.C))
        assertEquals("Field Theorist", ClassRules.displayName(theorist, Rank.B))
        assertEquals("Architect of Laws", ClassRules.displayName(theorist, Rank.S))
        assertTrue(ClassRules.evolvesAt(Rank.B) && ClassRules.evolvesAt(Rank.S) && !ClassRules.evolvesAt(Rank.A))
        assertTrue(ClassRules.validChoice("theorist", s, classes))
        assertFalse(ClassRules.validChoice("vanguard", s, classes))
        assertEquals(listOf(Effect.Resonance(0.05, 0.15)), ClassRules.effects(classes.byId("polymath")))
    }

    @Test
    fun `rank trials - unlock, two objectives per realm, Body within the cap, pass, fail, retry`() {
        val r = content.rankTrials
        assertEquals(null, RankTrialRules.available(Rank.E, 9, null, 100, r))
        assertEquals(Rank.D, RankTrialRules.available(Rank.E, 30, null, 100, r)!!.rank) // one rank at a time
        assertEquals(null, RankTrialRules.available(Rank.E, 30, retryDay = 105, today = 100, content = r))
        assertEquals(null, RankTrialRules.available(Rank.S, 200, null, 100, r))

        val efforts = RankTrialRules.realmEfforts(effort = 0.35, bodyHeadroom = 0.10)
        assertEquals(0.10, efforts[Realm.BODY]!!, 1e-9)
        assertEquals(0.475, efforts[Realm.MIND]!!, 1e-9) // (1.05 − 0.10) / 2
        assertEquals(3 * 0.35, efforts.values.sum(), 1e-9)

        val objectives = RankTrialRules.objectives(r.forRank(Rank.C)!!, r, seed = 5, bodyHeadroom = 0.10) { 40.0 }
        assertEquals(6, objectives.size)
        Realm.entries.forEach { realm -> assertEquals(2, objectives.count { it.realm == realm }) }
        assertTrue(objectives.all { it.target > 0 })
        assertEquals(objectives, RankTrialRules.objectives(r.forRank(Rank.C)!!, r, seed = 5, bodyHeadroom = 0.10) { 40.0 })

        assertEquals(TrialOutcome.PASSED, RankTrialRules.outcome(List(6) { 3.0 to 3.0 }))
        assertEquals(TrialOutcome.FAILED, RankTrialRules.outcome(List(5) { 3.0 to 3.0 } + (2.0 to 3.0)))
        assertEquals(GateRules.payoutXp(Rank.D, 12, 0.5, false), RankTrialRules.failXp(r.forRank(Rank.D)!!, 12, 0.5))
        assertEquals(107L, RankTrialRules.retryDay(100, r))
    }
}
