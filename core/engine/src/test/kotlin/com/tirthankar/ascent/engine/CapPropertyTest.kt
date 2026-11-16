package com.tirthankar.ascent.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 6: the bonus cap (A10, A11, A16) and Limit Break (A1), as property tests. */
class CapPropertyTest {

    private val content = TestContent.bundle
    private val rng = Random(2026)

    private fun randomGear(): List<Effect> {
        val pieces = List(rng.nextInt(0, 8)) {
            val affixes = List(2) {
                when (rng.nextInt(3)) {
                    0 -> Affix(AffixType.XP_REALM, rng.nextDouble(0.01, 0.12), realm = Realm.entries.random(rng))
                    1 -> Affix(AffixType.CRIT, rng.nextDouble(0.01, 0.12))
                    else -> Affix(AffixType.MASTERY_STAT, rng.nextDouble(0.01, 0.12), stat = Stat.entries.random(rng))
                }
            }
            GearPiece(affixes, rng.nextInt(0, 6))
        } + content.items.items.shuffled(rng).take(rng.nextInt(0, 4)).map { GearPiece(it.affixes, rng.nextInt(0, 6), it.unique, it.setId) }
        return GearRules.fold(pieces, content.items.sets).effects
    }

    private fun randomSkills(): List<Effect> =
        SkillRules.passiveEffects(content.skills.skills.filter { rng.nextBoolean() }.map { it.id }.toSet(), content.skills)

    private fun randomClass(): List<Effect> =
        if (rng.nextBoolean()) ClassRules.effects(content.classes.classes.random(rng)) else emptyList()

    @Test
    fun `passive multiplier never exceeds x1_75 and gear alone never exceeds +30 percent`() {
        repeat(5_000) {
            val quest = QuestRewardInput(Realm.entries.random(rng), Tier.entries.random(rng), setOf(Stat.entries.random(rng)), tags = setOf(QuestTags.SLEEP, QuestTags.REST_DAY).filter { rng.nextBoolean() }.toSet())
            val gear = randomGear()
            val effects = randomSkills() + randomClass() + if (rng.nextBoolean()) listOf(Effect.XpMult(value = 1.10)) else emptyList()
            val chains = mapOf("body" to rng.nextInt(0, 40), "mind_focus" to rng.nextInt(0, 40))
            val m = RewardCalculator.xpMultiplier(quest, effects, gear, GearCaps(), chains, rng.nextInt(0, 4))
            assertTrue(m <= 1.0 + RewardCalculator.PASSIVE_CAP + 1e-9, "multiplier $m")
            assertTrue(RewardCalculator.xpMultiplier(quest, emptyList(), gear) <= 1.30 + 1e-9)
        }
    }

    @Test
    fun `the streak stays outside the cap, powers and crit sit on top`() {
        val quest = QuestRewardInput(Realm.MIND, Tier.NORMAL, setOf(Stat.INT))
        val maxed = listOf(Effect.XpMult(value = 2.0)) // +100% passive, capped to +75%
        val context = RewardContext(level = 1, streakDays = 100, difficulty = 1.0, baseCritChance = 0.0, effects = maxed)
        val calc = RewardCalculator(Random(1))
        // 80 × 1.05 × 1.5 (streak) × 1.75 (cap) = 220.5 → 221
        assertEquals(221L, calc.questReward(quest, context).xp)
        // Limit Break ×3 on top: 661.5 → 662
        assertEquals(662L, calc.questReward(quest.copy(powerMultiplier = PowerRules.LIMIT_BREAK_XP), context).xp)
        // A crit doubles last.
        val crit = context.copy(effects = maxed + Effect.CritBonus(1.0))
        assertEquals(1323L, calc.questReward(quest.copy(powerMultiplier = PowerRules.LIMIT_BREAK_XP), crit).xp)
    }

    @Test
    fun `Limit Break never goes above the week's cap`() {
        repeat(5_000) {
            val unit = listOf(TargetUnit.REPS, TargetUnit.SECONDS, TargetUnit.STEPS, TargetUnit.MINUTES).random(rng)
            val last = (rng.nextInt(2, 200) * unit.fineStep)
            val week = rng.nextLong(0, 100)
            val sameWeek = rng.nextBoolean()
            val cap = if (sameWeek) SafetyRules.weeklyCap(last, unit) else SafetyRules.weeklyCap(last, unit)
            val memory = BodyTargetMemory(if (sameWeek) week else week - 7, cap, last)
            val current = (last * rng.nextDouble(0.5, 1.1)).let { TargetScaler.floorToStep(it, unit.fineStep) }.coerceAtLeast(unit.fineStep)
            val target = PowerRules.limitBreakTarget(current, memory, week, unit)
            val weekCap = if (sameWeek) memory.weekCap else SafetyRules.weeklyCap(memory.lastTarget, unit)
            if (target != null) {
                assertTrue(target <= weekCap + 1e-9, "$target > $weekCap")
                assertTrue(target > current)
            }
        }
        assertEquals(null, PowerRules.limitBreakTarget(30.0, null, 7, TargetUnit.REPS))
        assertEquals(33.0, PowerRules.limitBreakTarget(30.0, BodyTargetMemory(0, 30.0, 30.0), 7, TargetUnit.REPS))
        assertEquals(null, PowerRules.limitBreakTarget(33.0, BodyTargetMemory(7, 33.0, 33.0), 7, TargetUnit.REPS))
    }
}
