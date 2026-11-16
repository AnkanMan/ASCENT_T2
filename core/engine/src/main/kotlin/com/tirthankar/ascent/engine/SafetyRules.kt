package com.tirthankar.ascent.engine

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min

/**
 * Body deload tracking. Each week starts by comparing the Body multiplier with last
 * week's; every fourth rising week becomes a 60% deload week (SPEC override).
 */
data class BodyLoad(
    val lastWeekMultiplier: Double = DifficultyController.INITIAL_MULTIPLIER,
    val risingWeeks: Int = 0,
    val deload: Boolean = false,
)

/** What a Body template was capped at, so next week rises at most 10% from it. */
data class BodyTargetMemory(
    /** Epoch day of the week's first day (Sunday, D9). */
    val weekStartDay: Long,
    /** The highest target allowed this week. */
    val weekCap: Double,
    /** The last non-deload target issued: next week's cap is 10% above it. */
    val lastTarget: Double,
)

/**
 * Safety floors that hold at every difficulty and strictness (SPEC, CLAUDE.md invariants):
 * Body targets rise at most 10% per week; every fourth rising week is a 60% deload;
 * at least one rest day per week; sleep targets never below 7 h; no fasting or
 * calorie-restriction quests.
 */
object SafetyRules {

    const val BODY_WEEKLY_RISE = 1.10
    const val DELOAD_FACTOR = 0.6
    const val DELOAD_EVERY = 4
    const val MIN_SLEEP_HOURS = 7L
    const val MIN_REST_DAYS = 1

    // ---- Deload ----

    /** Called at each weekly reset with the current Body multiplier. */
    fun startWeek(load: BodyLoad, bodyMultiplier: Double): BodyLoad {
        val rising = bodyMultiplier > load.lastWeekMultiplier + 1e-9
        if (!rising) return BodyLoad(bodyMultiplier, load.risingWeeks, deload = false)
        val count = load.risingWeeks + 1
        return if (count >= DELOAD_EVERY) {
            BodyLoad(bodyMultiplier, risingWeeks = 0, deload = true)
        } else {
            BodyLoad(bodyMultiplier, risingWeeks = count, deload = false)
        }
    }

    // ---- +10% per week ----

    /**
     * Next week's cap for a target that was last [previous]: the largest friendly step
     * within +10%, or a finer step when the friendly one would jump more than 10%.
     */
    fun weeklyCap(previous: Double, unit: TargetUnit): Double {
        val limit = previous * BODY_WEEKLY_RISE
        val friendly = TargetScaler.floorToStep(limit, unit.step)
        if (friendly > previous + 1e-9) return friendly
        return max(previous, TargetScaler.floorToStep(limit, unit.fineStep))
    }

    /**
     * Caps a proposed Body target (sized without the deload factor) and returns the
     * target to issue plus the updated memory.
     *
     * - Within a week the target never exceeds that week's cap.
     * - A new week's cap is at most 10% above last week's highest target.
     * - A deload week issues 60% of the capped target and doesn't raise the cap, so the
     *   week after it resumes from the pre-deload level.
     */
    fun capBodyTarget(
        proposed: Double,
        memory: BodyTargetMemory?,
        weekStartDay: Long,
        deload: Boolean,
        unit: TargetUnit,
    ): Pair<Double, BodyTargetMemory> {
        val sameWeek = memory != null && memory.weekStartDay == weekStartDay
        val cap = when {
            memory == null -> null
            sameWeek -> memory.weekCap
            deload -> memory.lastTarget
            else -> weeklyCap(memory.lastTarget, unit)
        }
        val capped = if (cap == null) proposed else min(proposed, cap)
        if (deload) {
            val target = TargetScaler.scaledTarget(capped, DELOAD_FACTOR, unit)
            return target to BodyTargetMemory(weekStartDay, cap ?: capped, memory?.lastTarget ?: capped)
        }
        val last = if (sameWeek) max(memory.lastTarget, capped) else capped
        return capped to BodyTargetMemory(weekStartDay, cap ?: capped, last)
    }

    // ---- Rest, sleep, forbidden quests ----

    fun hasEnoughRestDays(restDays: Set<DayOfWeek>): Boolean = restDays.size >= MIN_REST_DAYS

    /** The latest lights-out that still allows 7 h before you wake. */
    fun latestLightsOut(wakeTime: LocalTime): LocalTime = wakeTime.minusHours(MIN_SLEEP_HOURS)

    fun sleepTargetHours(proposedHours: Double): Double = max(proposedHours, MIN_SLEEP_HOURS.toDouble())

    private val forbidden = Regex(
        listOf(
            """\bfasting\b""",
            """\bfasted\b""",
            """\bfast\s+for\s+\d+\s*(h|hr|hrs|hours?|days?)\b""",
            """\bwater\s+fast""",
            """\b\d+\s*(h|hr|hrs|hours?)\s+fast""",
            """\bintermittent\b""",
            """\bomad\b""",
            """\bcalori""",
            """\bkcal\b""",
            """\bskip(ping)?\s+(a\s+|the\s+)?(meals?|breakfast|lunch|dinner)\b""",
            """\bno\s+(food|eating|meals?|dinner|breakfast|lunch)\b""",
            """\bdon'?t\s+eat\b""",
            """\bstarv""",
            """\bweight[\s-]*(loss|goal)""",
            """\blose\s+(\d+\s*)?(kg|kgs|lb|lbs|pounds|weight)\b""",
            """\bdiet(ing)?\b""",
            """\bcut(ting)?\s+carbs\b""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    /** True for fasting, calorie-restriction or weight-loss quests, which are never issued. */
    fun isForbidden(text: String): Boolean = forbidden.containsMatchIn(text)
}
