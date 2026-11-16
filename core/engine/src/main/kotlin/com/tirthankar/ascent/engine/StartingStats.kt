package com.tirthankar.ascent.engine

import kotlin.math.floor
import kotlin.math.min

/** One Awakening measurement: where you are and where you want to be. */
data class Measurement(
    val current: Double,
    val goal: Double,
    /** True for times, e.g. a 1 km run: a smaller number is better. */
    val lowerIsBetter: Boolean = false,
) {
    init {
        require(current >= 0) { "current must be >= 0, was $current" }
        require(goal > 0) { "goal must be > 0, was $goal" }
    }

    /** p in [0, 1]: how far along you are toward your own goal. */
    val progress: Double
        get() = if (lowerIsBetter) {
            if (current <= 0.0) 1.0 else min(goal / current, 1.0)
        } else {
            min(current / goal, 1.0)
        }
}

/** The Awakening measurements that set starting stats. A missing one leaves its stat at 10. */
data class AwakeningMeasurements(
    val pushUps: Measurement? = null,
    val squats: Measurement? = null,
    /** Average daily steps, or a 1 km time in minutes with lowerIsBetter. */
    val agility: Measurement? = null,
    val deepWorkHours: Measurement? = null,
    val pagesPerWeek: Measurement? = null,
    val sleepHours: Measurement? = null,
)

/**
 * Starting stats (SPEC "Character creation", decided 2026-11-11 as D5):
 * each stat starts at 10 and shifts by round(10·p − 5), so −5 to +5.
 * STR is the mean of the push-up and squat shifts. FOC has no measurement.
 */
object StartingStats {

    fun shift(measurement: Measurement): Int = roundHalfUp(10 * measurement.progress - 5)

    fun compute(measurements: AwakeningMeasurements): StatBlock {
        val strShifts = listOfNotNull(measurements.pushUps, measurements.squats).map(::shift)
        val strShift = if (strShifts.isEmpty()) 0 else roundHalfUp(strShifts.average())
        fun stat(shift: Int) = StatBlock.STARTING_VALUE + shift
        return StatBlock(
            str = stat(strShift),
            agi = stat(measurements.agility?.let(::shift) ?: 0),
            int = stat(measurements.deepWorkHours?.let(::shift) ?: 0),
            foc = StatBlock.STARTING_VALUE,
            wis = stat(measurements.pagesPerWeek?.let(::shift) ?: 0),
            vit = stat(measurements.sleepHours?.let(::shift) ?: 0),
        )
    }

    private fun roundHalfUp(x: Double): Int = floor(x + 0.5 + 1e-9).toInt()
}
