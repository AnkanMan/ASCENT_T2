package com.tirthankar.ascent.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewardCalculatorTest {

    private val mindQuest = QuestRewardInput(Realm.MIND, Tier.NORMAL, setOf(Stat.INT))
    private val bodyQuest = QuestRewardInput(Realm.BODY, Tier.NORMAL, setOf(Stat.STR))
    private val noCrit = RewardContext(level = 10, streakDays = 0, difficulty = 1.0, baseCritChance = 0.0)

    private fun xp(quest: QuestRewardInput, context: RewardContext) =
        RewardCalculator(Random(1)).questReward(quest, context).xp

    @Test
    fun `no effects matches the base formula`() {
        val expected = QuestXp.xp(Tier.NORMAL, 1.0, 10, 0, isCritical = false)
        assertEquals(expected, xp(mindQuest, noCrit))
        assertEquals(120L, expected)
    }

    @Test
    fun `xp multipliers stack additively`() {
        val effects = listOf(Effect.XpMult(value = 1.10), Effect.XpMult(realm = Realm.MIND, value = 1.15))
        assertEquals(1.25, RewardCalculator.xpMultiplier(mindQuest, effects), 1e-12)
        assertEquals(150L, xp(mindQuest, noCrit.copy(effects = effects))) // 120 × 1.25
    }

    @Test
    fun `realm-scoped multipliers leave other realms alone`() {
        val effects = listOf(Effect.XpMult(realm = Realm.MIND, value = 1.15))
        assertEquals(1.0, RewardCalculator.xpMultiplier(bodyQuest, effects), 1e-12)
        assertEquals(120L, xp(bodyQuest, noCrit.copy(effects = effects)))
    }

    @Test
    fun `stat-scoped multipliers need the stat on the quest`() {
        val effects = listOf(Effect.XpMult(stat = Stat.INT, value = 1.10))
        assertEquals(1.10, RewardCalculator.xpMultiplier(mindQuest, effects), 1e-12)
        assertEquals(1.0, RewardCalculator.xpMultiplier(bodyQuest, effects), 1e-12)
    }

    @Test
    fun `streak_cap raises the cap and the highest wins`() {
        val longStreak = noCrit.copy(streakDays = 100)
        assertEquals(180L, xp(mindQuest, longStreak)) // 120 × 1.5
        val effects = listOf(Effect.StreakCap(1.6), Effect.StreakCap(1.55))
        assertEquals(1.6, RewardCalculator.streakCap(effects), 1e-12)
        assertEquals(192L, xp(mindQuest, longStreak.copy(effects = effects))) // 120 × 1.6
    }

    @Test
    fun `mastery multipliers apply only to their stat`() {
        val effects = listOf(Effect.MasteryMult(Stat.STR, 1.10), Effect.MasteryMult(Stat.STR, 1.20))
        assertEquals(1.30, RewardCalculator.masteryMultiplier(Stat.STR, effects), 1e-12)
        assertEquals(1.0, RewardCalculator.masteryMultiplier(Stat.AGI, effects), 1e-12)
    }

    @Test
    fun `crit chance adds bonuses and stays in 0 to 1`() {
        assertEquals(0.10, RewardCalculator.critChance(noCrit.copy(baseCritChance = 0.05, effects = listOf(Effect.CritBonus(0.05)))), 1e-12)
        assertEquals(1.0, RewardCalculator.critChance(noCrit.copy(baseCritChance = 0.25, effects = listOf(Effect.CritBonus(0.9)))), 1e-12)
    }

    @Test
    fun `zero chance never crits, full chance always crits`() {
        val calculator = RewardCalculator(Random(42))
        repeat(200) { assertFalse(calculator.questReward(mindQuest, noCrit).isCritical) }
        val always = noCrit.copy(baseCritChance = 0.0, effects = listOf(Effect.CritBonus(1.0)))
        repeat(200) {
            val reward = calculator.questReward(mindQuest, always)
            assertTrue(reward.isCritical)
            assertEquals(240L, reward.xp)
        }
    }

    @Test
    fun `a seeded crit roll is reproducible`() {
        val context = noCrit.copy(baseCritChance = 0.25)
        val first = RewardCalculator(Random(7)).let { c -> List(50) { c.questReward(mindQuest, context) } }
        val second = RewardCalculator(Random(7)).let { c -> List(50) { c.questReward(mindQuest, context) } }
        assertEquals(first, second)
        assertTrue(first.any { it.isCritical } && first.any { !it.isCritical })
    }
}
