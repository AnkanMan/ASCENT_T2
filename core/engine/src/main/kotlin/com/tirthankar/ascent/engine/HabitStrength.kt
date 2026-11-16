package com.tirthankar.ascent.engine

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** How often a series is issued: one observation per game day or per week. */
enum class SeriesPeriod { DAY, WEEK }

/** Who forgave a miss. Recorded for honesty in tests and exports; strength ignores it (D3). */
enum class Forgiveness { NONE, STREAK_SHIELD, UNBROKEN }

/** What happened to a series in one period (PHASE5.md section 1). */
sealed interface PeriodOutcome {
    /** Not issued that period: rest day, swapped out by Second Wind, retired. No update. */
    data object NotIssued : PeriodOutcome

    /** A restore gap (D7): unknown, not missed. No update. */
    data object Neutral : PeriodOutcome

    /** Issued: [progress] against that period's own [target]. A power's [forgiveness] never counts (D3). */
    data class Issued(
        val progress: Double,
        val target: Double,
        val forgiveness: Forgiveness = Forgiveness.NONE,
    ) : PeriodOutcome
}

/** One recorded observation: [periodKey] orders them (epoch day of the period's start). */
data class StrengthObservation(val periodKey: Long, val x: Double)

/** Strength's shown buckets (PHASE5.md section 1). */
enum class StrengthLabel { UNSTABLE, FORMING, HOLDING, BOUND }

/** A series' state, computed from its full history. */
data class StrengthSummary(
    val strength: Double,
    val percent: Int,
    val label: StrengthLabel,
    /** Completed periods in a row, counting back from the latest observation. */
    val streak: Int,
    val bestStreak: Int,
    val observations: Int,
    /** The first observed period, for "measuring since <date>". */
    val sinceKey: Long?,
) {
    /** Fewer than 30 periods: shown as "measuring since". */
    val measuring: Boolean get() = observations < HabitStrength.MEASURING_PERIODS
}

/**
 * Habit strength (PHASE5.md section 1, D1–D3): an exponential moving average of how
 * fully a repeating quest was done each period it was issued.
 *
 *     S_k = (1 − α) · S_{k−1} + α · x_k,   S_0 = 0,   x = min(progress / target, 1)
 *
 * α is chosen so a perfect record reaches 80% after 30 days, whatever the period:
 * α_day = 1 − 0.2^(1/30), α_week = 1 − 0.2^(7/30). Periods that weren't issued, and
 * neutral restore gaps, don't update S at all. Shield and Unbroken never touch it.
 */
object HabitStrength {

    /** A perfect record reaches [TARGET_LEVEL] after [TARGET_DAYS] days (D1). */
    const val TARGET_LEVEL = 0.8
    const val TARGET_DAYS = 30.0

    /** "Measuring since" shows until this many periods are observed. */
    const val MEASURING_PERIODS = 30

    /** ≈ 0.05223 */
    val ALPHA_DAY: Double = 1.0 - (1.0 - TARGET_LEVEL).pow(1.0 / TARGET_DAYS)

    /** ≈ 0.3131 (D2): the same memory in real time, one observation a week. */
    val ALPHA_WEEK: Double = 1.0 - (1.0 - TARGET_LEVEL).pow(7.0 / TARGET_DAYS)

    fun alpha(period: SeriesPeriod): Double = when (period) {
        SeriesPeriod.DAY -> ALPHA_DAY
        SeriesPeriod.WEEK -> ALPHA_WEEK
    }

    /** The e-folding time in days: ≈ 18.64 for both periods. */
    fun memoryDays(period: SeriesPeriod): Double {
        val days = if (period == SeriesPeriod.DAY) 1.0 else 7.0
        return -days / ln(1.0 - alpha(period))
    }

    /** x for an issued period, in 0..1. A non-positive target counts any progress as done. */
    fun x(progress: Double, target: Double): Double = when {
        target <= 0.0 -> if (progress > 0.0) 1.0 else 0.0
        else -> (progress / target).coerceIn(0.0, 1.0)
    }

    /** The observation for [outcome], or null when the period doesn't count. Forgiveness is ignored (D3). */
    fun observe(outcome: PeriodOutcome): Double? = when (outcome) {
        PeriodOutcome.NotIssued, PeriodOutcome.Neutral -> null
        is PeriodOutcome.Issued -> x(outcome.progress, outcome.target)
    }

    /** One step of the average. */
    fun next(previous: Double, x: Double, period: SeriesPeriod): Double {
        val a = alpha(period)
        return (1.0 - a) * previous + a * x.coerceIn(0.0, 1.0)
    }

    /** S after [outcomes], oldest first, from S_0 = 0. */
    fun fold(outcomes: List<PeriodOutcome>, period: SeriesPeriod): Double =
        outcomes.fold(0.0) { s, o -> observe(o)?.let { next(s, it, period) } ?: s }

    /**
     * S from the full stored history, sorted by period key so an edited past period
     * gives the same value as a fresh computation. One value per key (the last wins).
     */
    fun strength(history: List<StrengthObservation>, period: SeriesPeriod): Double =
        ordered(history).fold(0.0) { s, o -> next(s, o.x, period) }

    /** Integer percent, rounded half-up. */
    fun percent(strength: Double): Int = floor(strength.coerceIn(0.0, 1.0) * 100.0 + 0.5).toInt()

    /** The label for the shown percent, so the number and the word never disagree. */
    fun label(percent: Int): StrengthLabel = when {
        percent < 30 -> StrengthLabel.UNSTABLE
        percent < 60 -> StrengthLabel.FORMING
        percent < 85 -> StrengthLabel.HOLDING
        else -> StrengthLabel.BOUND
    }

    /** Strength, label, streaks and count for a series. A period counts toward a streak only when x = 1. */
    fun summarize(history: List<StrengthObservation>, period: SeriesPeriod): StrengthSummary {
        val sorted = ordered(history)
        var s = 0.0
        var run = 0
        var best = 0
        sorted.forEach { o ->
            s = next(s, o.x, period)
            run = if (o.x >= 1.0) run + 1 else 0
            best = maxOf(best, run)
        }
        val pct = percent(s)
        return StrengthSummary(s, pct, label(pct), run, best, sorted.size, sorted.firstOrNull()?.periodKey)
    }

    /** S after each observation, oldest first, keeping the last [last] values (the 90-period sparkline). */
    fun trajectory(history: List<StrengthObservation>, period: SeriesPeriod, last: Int = SPARKLINE_PERIODS): List<Double> {
        var s = 0.0
        return ordered(history).map { o -> next(s, o.x, period).also { s = it } }.takeLast(last.coerceAtLeast(0))
    }

    const val SPARKLINE_PERIODS = 90

    private fun ordered(history: List<StrengthObservation>): List<StrengthObservation> =
        history.associateBy { it.periodKey }.values.sortedBy { it.periodKey }
}

/**
 * Period keys for habit series: the game day's epoch day, or the epoch day of its
 * week's Sunday. Built from a game day, so they already follow the reset time and the
 * player's current time zone ([GameClock]).
 */
object HabitPeriods {

    fun key(period: SeriesPeriod, gameDay: LocalDate): Long = when (period) {
        SeriesPeriod.DAY -> gameDay.toEpochDay()
        SeriesPeriod.WEEK -> GameClock.weekStart(gameDay).toEpochDay()
    }

    /** The first day of the period [key] names. */
    fun start(key: Long): LocalDate = LocalDate.ofEpochDay(key)
}

/** Series ids (stored on quests and observations). */
object HabitSeries {

    private const val TEMPLATE = "t:"
    private const val RECURRING = "r:"

    /** One series per template key, across every day it is issued. */
    fun forTemplate(templateKey: String): String = TEMPLATE + templateKey

    /** A new recurring quest starts its own series, named after its first quest's public id. */
    fun forRecurring(firstPublicId: String): String = RECURRING + firstPublicId

    fun isTemplate(seriesId: String): Boolean = seriesId.startsWith(TEMPLATE)

    fun templateKey(seriesId: String): String? = seriesId.takeIf(::isTemplate)?.removePrefix(TEMPLATE)
}
