package com.tirthankar.ascent.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.min

/** How often a skill was used in its current period (stored per skill). */
data class SkillUse(
    /** The period the [usesInPeriod] count belongs to, e.g. "w20403". */
    val periodKey: String? = null,
    val usesInPeriod: Int = 0,
    val lastCastAt: Instant? = null,
)

/**
 * Game periods (PHASE4.md section 1): the day starts at the reset time, the week runs
 * Sunday to Saturday, the month is the calendar month of the game day. Keys are built
 * from a game day, so they already follow the player's reset time.
 */
object Periods {

    const val MAX_COOLDOWN_REDUCTION = 0.40

    fun dayKey(gameDay: LocalDate): String = "d${gameDay.toEpochDay()}"

    fun weekKey(gameDay: LocalDate): String = "w${GameClock.weekStart(gameDay).toEpochDay()}"

    fun monthKey(gameDay: LocalDate): String = "m${gameDay.year}-${gameDay.monthValue}"

    /** The key for [period]; hour cooldowns have no period key. */
    fun key(period: LimitPeriod, gameDay: LocalDate): String? = when (period) {
        LimitPeriod.DAY -> dayKey(gameDay)
        LimitPeriod.WEEK -> weekKey(gameDay)
        LimitPeriod.MONTH -> monthKey(gameDay)
        LimitPeriod.HOURS -> null
    }

    /**
     * Cooldown reduction for [skillId]: FOC (1% per point) plus matching gear effects
     * (Observer's Regalia), together at most 40% (A12). Only hour cooldowns use it.
     */
    fun cooldownReduction(stats: StatBlock, effects: List<Effect>, skillId: String): Double {
        val gear = effects.filterIsInstance<Effect.CooldownReduction>()
            .filter { it.skill == null || it.skill == skillId }
            .sumOf { it.value }
        return min(MAX_COOLDOWN_REDUCTION, DerivedStats.cooldownReduction(stats) + gear)
    }

    /** The cooldown after reduction. */
    fun cooldown(hours: Int, reduction: Double): Duration =
        Duration.ofSeconds((hours * 3600 * (1.0 - reduction.coerceIn(0.0, MAX_COOLDOWN_REDUCTION))).toLong())

    /** Uses left in the current period ([allowed] per period). Reductions never touch these. */
    fun usesLeft(period: LimitPeriod, allowed: Int, use: SkillUse?, gameDay: LocalDate): Int {
        val key = key(period, gameDay) ?: return allowed
        val used = if (use?.periodKey == key) use.usesInPeriod else 0
        return (allowed - used).coerceAtLeast(0)
    }

    /** Time left on an hour cooldown, or zero when ready. */
    fun cooldownLeft(hours: Int, reduction: Double, lastCastAt: Instant?, now: Instant): Duration {
        if (lastCastAt == null) return Duration.ZERO
        val readyAt = lastCastAt.plus(cooldown(hours, reduction))
        return if (now.isBefore(readyAt)) Duration.between(now, readyAt) else Duration.ZERO
    }

    /** The use record after one more cast now. */
    fun recordUse(period: LimitPeriod, use: SkillUse?, gameDay: LocalDate, now: Instant): SkillUse {
        val key = key(period, gameDay)
        val previous = if (key != null && use?.periodKey == key) use.usesInPeriod else 0
        return SkillUse(key, previous + 1, now)
    }
}
