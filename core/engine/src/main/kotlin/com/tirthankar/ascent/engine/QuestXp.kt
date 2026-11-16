package com.tirthankar.ascent.engine

import kotlin.math.floor
import kotlin.math.min

/**
 * XP paid by one quest (SPEC):
 * B_tier × d_realm × (1 + 0.05·L) × min(1 + 0.02·s, cap) × c
 *
 * [bonusMultiplier] is where RewardCalculator folds in effects; it is 1.0 with none.
 * The result is rounded half-up to a whole XP.
 */
object QuestXp {

    const val DEFAULT_STREAK_CAP = 1.5
    const val CRIT_MULTIPLIER = 2.0

    fun levelMultiplier(level: Int): Double {
        require(level >= 1) { "level must be >= 1, was $level" }
        return 1.0 + 0.05 * level
    }

    fun streakMultiplier(streakDays: Int, cap: Double = DEFAULT_STREAK_CAP): Double {
        require(streakDays >= 0) { "streakDays must be >= 0, was $streakDays" }
        return min(1.0 + 0.02 * streakDays, cap)
    }

    fun xp(
        tier: Tier,
        difficulty: Double,
        level: Int,
        streakDays: Int,
        isCritical: Boolean,
        streakCap: Double = DEFAULT_STREAK_CAP,
        bonusMultiplier: Double = 1.0,
    ): Long {
        require(difficulty > 0) { "difficulty must be > 0, was $difficulty" }
        require(bonusMultiplier >= 0) { "bonusMultiplier must be >= 0, was $bonusMultiplier" }
        val raw = tier.baseXp *
            difficulty *
            levelMultiplier(level) *
            streakMultiplier(streakDays, streakCap) *
            (if (isCritical) CRIT_MULTIPLIER else 1.0) *
            bonusMultiplier
        return floor(raw + 0.5).toLong()
    }
}
