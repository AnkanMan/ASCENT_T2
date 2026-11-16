package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase 4 content (Tasks 1–3): skills, classes, rank trials, avatar, new lore lines. */
class ProgressionContentTest {

    private val content = TestContent.bundle

    @Test
    fun `skills match the drawn trees`() {
        val s = content.skills
        val realm = s.skills.filter { it.tree in setOf(SkillTree.BODY, SkillTree.MIND, SkillTree.SELF) }
        assertEquals(21, realm.size)
        // Chains from PHASE4.md section 1.
        val chains = mapOf(
            "momentum" to "iron_body", "limit_break" to "momentum", "second_wind" to "light_feet", "recovery_aura" to "second_wind",
            "hyperfocus" to "deep_work", "focus_chain" to "hyperfocus", "insight" to "clear_mind", "literature_sense" to "insight",
            "streak_shield" to "restful", "bonds" to "streak_shield", "clarity" to "curious", "mentors_eye" to "clarity",
        )
        chains.forEach { (skill, pre) -> assertEquals(listOf(pre), s.skill(skill)!!.prerequisites, skill) }
        assertEquals(setOf("limit_break", "recovery_aura"), s.skill("unbroken")!!.prerequisites.toSet())
        assertEquals(setOf("focus_chain", "literature_sense"), s.skill("eigenstate_collapse")!!.prerequisites.toSet())
        assertEquals(setOf("bonds", "mentors_eye"), s.skill("equilibrium")!!.prerequisites.toSet())
        assertEquals(mapOf(2 to 20, 3 to 35, 4 to 50), s.tierGates)
        assertEquals(50, s.respecEssence)
    }

    @Test
    fun `actives carry the costs and limits from PHASE4`() {
        fun skill(id: String) = content.skills.skill(id)!!
        assertEquals(20, skill("second_wind").mp)
        assertEquals(LimitPeriod.DAY, skill("second_wind").limit!!.period)
        assertEquals(LimitPeriod.WEEK, skill("limit_break").limit!!.period)
        assertEquals(50 to LimitPeriod.MONTH, skill("unbroken").mp to skill("unbroken").limit!!.period)
        assertEquals(12, skill("hyperfocus").limit!!.hours)
        assertEquals(20, skill("insight").limit!!.wisPerCharge)
        assertEquals(80, skill("eigenstate_collapse").mp)
        assertEquals(30 to LimitPeriod.WEEK, skill("mentors_eye").mp to skill("mentors_eye").limit!!.period)
        assertEquals(15, skill("clarity").mp)
        assertTrue(content.skills.skills.filter { it.kind == SkillKind.ACTIVE && !it.locked }.all { it.handler in PowerHandlers.ALL })
    }

    @Test
    fun `passives are JSON effects`() {
        fun effects(id: String) = content.skills.skill(id)!!.effects
        assertEquals(listOf(Effect.MasteryMult(Stat.STR, 1.10)), effects("iron_body"))
        assertEquals(listOf(Effect.ChainXp(Realm.BODY, 0.03, 0.15, "body")), effects("momentum"))
        assertEquals(listOf(Effect.ChainXp(Realm.MIND, 0.05, 0.25, "mind_focus")), effects("focus_chain"))
        assertEquals(listOf(Effect.RestHpMult(1.5)), effects("recovery_aura"))
        assertEquals(listOf(Effect.StreakShield(perWeek = 1)), effects("streak_shield"))
        assertEquals(listOf(Effect.TagHp("social", 10)), effects("bonds"))
        assertEquals(listOf(Effect.DualMastery("paper", listOf(Stat.INT, Stat.WIS))), effects("literature_sense"))
        assertEquals(listOf(Effect.RestRestore(0.10)), effects("equilibrium"))
    }

    @Test
    fun `the Observer tree exists and is locked`() {
        val observer = content.skills.skills.filter { it.tree == SkillTree.OBSERVER }
        assertTrue(observer.isNotEmpty() && observer.all { it.locked && it.effects.isEmpty() })
    }

    @Test
    fun `six classes with bonuses, evolutions, unique skills and avatar layers`() {
        val classes = content.classes.classes
        assertEquals(setOf("theorist", "computationalist", "vanguard", "ascetic", "wanderer", "polymath"), classes.map { it.id }.toSet())
        val theorist = content.classes.byId("theorist")!!
        assertEquals(listOf(Stat.INT, Stat.WIS), theorist.stats)
        assertEquals(listOf("Field Theorist", "Architect of Laws"), theorist.evolutions)
        assertTrue(Effect.MasteryMult(Stat.INT, 1.10) in theorist.bonus) // A13
        assertEquals(PowerHandlers.OVERLOAD, content.classes.byId("vanguard")!!.unique.handler)
        assertEquals(listOf(Effect.Resonance(0.05, 0.15)), content.classes.byId("polymath")!!.unique.effects)
        assertEquals(1.15, content.classes.polymathRatio)
        classes.forEach { assertNotNull(content.avatar.classLayers[it.id], it.id) }
    }

    @Test
    fun `rank trials follow the SPEC thresholds and Gate efforts`() {
        val r = content.rankTrials
        assertEquals(listOf(10, 25, 45, 70, 100), r.trials.map { it.level })
        assertEquals(7, r.days)
        assertEquals(2, r.objectivesPerRealm)
        assertEquals(7, r.retryDays)
        assertEquals(0.20, r.forRank(Rank.D)!!.effort)
        assertEquals(DropSource.CHEST_AS, r.forRank(Rank.S)!!.chest)
        assertTrue(Realm.entries.all { (r.objectives[it]?.size ?: 0) >= 2 })
    }

    @Test
    fun `avatar rules - an aura for every rank, particles and rings`() {
        val a = content.avatar
        assertEquals(Rank.entries.toSet(), a.auras.keys)
        assertEquals(AuraToken.SYSTEM_BLUE, a.auras[Rank.D])
        assertEquals(8 to 60, a.particleBase to a.particleMax)
        assertEquals(10 to 10, a.ringPerLevels to a.ringMax)
    }

    @Test
    fun `new lore lines are valid, unique, and every trigger exists`() {
        val phase4Kinds = setOf(
            LoreKinds.RANK_TRIAL_START, LoreKinds.RANK_TRIAL_FAIL, LoreKinds.RANK, LoreKinds.SIGMA_HINT, LoreKinds.CLASS,
            LoreKinds.CLASS_EVOLUTION, LoreKinds.SKILL_FIRST, LoreKinds.POWER_FIRST_CAST, LoreKinds.SKILL_UNLOCK,
            LoreKinds.OBSERVER_GLIMPSE, LoreKinds.RESPEC,
        )
        val added = content.lore.entries.filter { it.unlock.kind in phase4Kinds }
        assertEquals(33, added.size)
        assertTrue(added.all { LoreRules.isValid(it.text) && it.text.length <= LoreRules.MAX_LENGTH })
        assertEquals(content.lore.entries.size, content.lore.entries.map { it.id }.toSet().size)
        // Validation (run in ContentTest) also checks every trigger has a line.
        assertEquals(emptyList(), ContentValidator.validate(content).filter { it.startsWith("lore") })
    }

    @Test
    fun `every lore line has a Record category`() {
        val byCategory = content.lore.entries.groupBy { it.recordCategory }
        assertEquals(LoreCategory.entries.toSet(), byCategory.keys)
        assertEquals(LoreCategory.CLASSES, content.lore.entries.first { it.id == "lore_final_titan" }.recordCategory)
        assertEquals(LoreCategory.RANKS, content.lore.entries.first { it.id == "lore_rank_d" }.recordCategory)
        assertEquals(LoreCategory.WORLD, content.lore.entries.first { it.id == "lore_awakening" }.recordCategory)
    }

    @Test
    fun `validation catches broken Phase 4 content`() {
        val brokenSkills = content.skills.copy(
            skills = content.skills.skills.map { if (it.id == "momentum") it.copy(prerequisites = listOf("nowhere")) else it } +
                SkillDef("bad_power", "Bad", SkillTree.MIND, 1, SkillKind.ACTIVE, "x", handler = "nope", limit = SkillLimit(LimitPeriod.DAY)),
        )
        val brokenTrials = content.rankTrials.copy(trials = content.rankTrials.trials.map { if (it.rank == Rank.C) it.copy(level = 24) else it })
        val noClassLore = content.lore.copy(entries = content.lore.entries.filterNot { it.id == "lore_class_wanderer" })
        val problems = ContentValidator.validate(content.copy(skills = brokenSkills, rankTrials = brokenTrials, lore = noClassLore))
        assertTrue(problems.any { "unknown prerequisite nowhere" in it }, problems.toString())
        assertTrue(problems.any { "unknown handler nope" in it }, problems.toString())
        assertTrue(problems.any { "MIND must have" in it }, problems.toString())
        assertTrue(problems.any { "C level 24" in it }, problems.toString())
        assertTrue(problems.any { "no line for class wanderer" in it }, problems.toString())
    }
}
