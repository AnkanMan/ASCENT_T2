package com.tirthankar.ascent.engine

import kotlin.math.max

/** A change in level. [toLevel] is never below [fromLevel]: a level is never lost. */
data class LevelChange(val fromLevel: Int, val toLevel: Int) {
    init {
        require(fromLevel >= 1) { "fromLevel must be >= 1, was $fromLevel" }
        require(toLevel >= fromLevel) { "a level is never lost ($fromLevel → $toLevel)" }
    }

    val leveledUp: Boolean get() = toLevel > fromLevel
    val levelsGained: Int get() = toLevel - fromLevel
    val freePoints: Int get() = levelsGained * Leveling.FREE_POINTS_PER_LEVEL
    val skillPoints: Int get() = Leveling.skillPointsBetween(fromLevel, toLevel)
}

/** Level-ups (SPEC): +3 free attribute points per level, 1 skill point every 2 levels. */
object Leveling {

    const val FREE_POINTS_PER_LEVEL = 3
    const val LEVELS_PER_SKILL_POINT = 2

    fun levelFor(totalXp: Long): Int = XpCurve.levelProgress(totalXp).level

    /** The level after XP becomes [newTotalXp], never below [storedLevel]. */
    fun change(storedLevel: Int, newTotalXp: Long): LevelChange =
        LevelChange(storedLevel, max(storedLevel, levelFor(newTotalXp)))

    /** Skill points earned for reaching each level in (from, to]: one at every even level. */
    fun skillPointsBetween(fromLevel: Int, toLevel: Int): Int =
        toLevel / LEVELS_PER_SKILL_POINT - fromLevel / LEVELS_PER_SKILL_POINT
}
