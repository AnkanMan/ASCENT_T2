package com.tirthankar.ascent.engine

import kotlin.math.floor
import kotlin.math.pow

/**
 * The level curve from SPEC: going from level n to n + 1 costs floor(100 · n^1.3) XP.
 * Levels start at 1 and have no cap.
 */
object XpCurve {

    private const val SCALE = 100.0
    private const val EXPONENT = 1.3

    /** XP needed to go from [level] to [level] + 1. */
    fun xpToNext(level: Int): Long {
        require(level >= 1) { "level must be >= 1, was $level" }
        return floor(SCALE * level.toDouble().pow(EXPONENT)).toLong()
    }

    /** Total XP earned since level 1 at the moment [level] is reached. */
    fun totalXpToReach(level: Int): Long {
        require(level >= 1) { "level must be >= 1, was $level" }
        var total = 0L
        for (n in 1 until level) total += xpToNext(n)
        return total
    }

    /** Where a player with [totalXp] lifetime XP stands on the curve. */
    fun levelProgress(totalXp: Long): LevelProgress {
        require(totalXp >= 0) { "totalXp must be >= 0, was $totalXp" }
        var level = 1
        var remaining = totalXp
        var cost = xpToNext(level)
        while (remaining >= cost) {
            remaining -= cost
            level++
            cost = xpToNext(level)
        }
        return LevelProgress(level = level, xpIntoLevel = remaining, xpForNext = cost)
    }
}

/** Position within the current level: [xpIntoLevel] of [xpForNext] earned. */
data class LevelProgress(
    val level: Int,
    val xpIntoLevel: Long,
    val xpForNext: Long,
) {
    /** Fill fraction of the XP bar, in [0, 1). */
    val fraction: Double get() = xpIntoLevel.toDouble() / xpForNext
}
