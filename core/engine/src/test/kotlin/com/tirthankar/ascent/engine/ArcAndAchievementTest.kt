package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcAndAchievementTest {

    private val defs = TestContent.bundle.achievements.achievements

    @Test
    fun `chapters pay 1,000 times the level multiplier and need every objective`() {
        assertEquals(1_500L, ArcRules.chapterXp(10))
        assertTrue(ArcRules.chapterComplete(listOf(3.0 to 3.0, 1.0 to 1.0)))
        assertFalse(ArcRules.chapterComplete(listOf(3.0 to 3.0, 0.0 to 1.0)))
        assertFalse(ArcRules.chapterComplete(emptyList())) // completed by hand
        assertTrue(ArcRules.chapterCountWarning(2))
        assertFalse(ArcRules.chapterCountWarning(5))
        assertTrue(ArcRules.startsActive(0))
        assertFalse(ArcRules.startsActive(1))
    }

    @Test
    fun `every seed achievement is earned at its threshold with its reward`() {
        defs.forEach { def ->
            val below = mapOf(def.metric to def.thresholds.first() - 1)
            assertTrue(AchievementEvaluator.newlyEarned(listOf(def), below, emptyMap()).isEmpty(), def.id)
            val at = mapOf(def.metric to def.thresholds.first())
            val earned = AchievementEvaluator.newlyEarned(listOf(def), at, emptyMap())
            assertEquals(1, earned.size, def.id)
            assertEquals(def.rewards.first(), earned.single().reward, def.id)
        }
    }

    @Test
    fun `specific rewards match SPEC`() {
        fun reward(id: String, tier: Int = 1) = defs.first { it.id == id }.rewards[tier - 1]
        assertEquals("toda_chain", reward("conserved_quantity").item)
        assertEquals("cloak_detailed_balance", reward("ergodic").item)
        assertEquals("boots_random_walker", reward("diffusive_limit").item)
        assertEquals(200, reward("deep_field", 3).essence)
        assertEquals(DropSource.CHEST_AS, reward("gatekeeper", 3).chest)
        assertTrue(defs.first { it.id == "still_night" }.hidden)
        assertTrue(defs.first { it.id == "red_sky" }.hidden)
    }

    @Test
    fun `a backfill pays every tier passed, and held tiers are not paid again`() {
        val deepField = defs.first { it.id == "deep_field" }
        val metrics = mapOf(AchievementMetrics.DEEP_WORK_HOURS to 150.0)
        assertEquals(listOf(1, 2), AchievementEvaluator.newlyEarned(listOf(deepField), metrics, emptyMap()).map { it.tier })
        assertEquals(listOf(2), AchievementEvaluator.newlyEarned(listOf(deepField), metrics, mapOf("deep_field" to 1)).map { it.tier })
        assertTrue(AchievementEvaluator.newlyEarned(listOf(deepField), metrics, mapOf("deep_field" to 2)).isEmpty())
    }

    @Test
    fun `equipartition and longest runs`() {
        assertTrue(AchievementMetrics.equipartition(StatBlock(10, 10, 11, 10, 10, 10)))
        assertFalse(AchievementMetrics.equipartition(StatBlock(10, 10, 12, 10, 10, 10)))
        assertEquals(3, AchievementMetrics.longestRun(listOf(1L, 2L, 3L, 5L, 6L)))
        assertEquals(0, AchievementMetrics.longestRun(emptyList()))
    }
}
