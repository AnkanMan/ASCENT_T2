package com.tirthankar.ascent.engine

import kotlin.math.floor
import kotlin.math.max

/**
 * Target units with their friendly rounding [step] (decided 2026-11-11) and a
 * [fineStep] used only when a safety cap forbids the next friendly step.
 */
enum class TargetUnit(val step: Double, val fineStep: Double) {
    REPS(5.0, 1.0),
    MINUTES(15.0, 1.0),
    STEPS(500.0, 100.0),
    PAGES(5.0, 1.0),
    KM(0.5, 0.1),
    SECONDS(15.0, 5.0),
    HOURS(0.5, 0.25),
    OTHER(1.0, 1.0),
    ;

    companion object {
        /** Maps a unit label (as the AI or the player writes it) to a unit; unknown labels are OTHER. */
        fun fromLabel(label: String): TargetUnit = when (label.trim().lowercase()) {
            "rep", "reps" -> REPS
            "min", "mins", "minute", "minutes" -> MINUTES
            "step", "steps" -> STEPS
            "page", "pages" -> PAGES
            "km" -> KM
            "s", "sec", "secs", "second", "seconds" -> SECONDS
            "h", "hr", "hrs", "hour", "hours" -> HOURS
            else -> OTHER
        }
    }
}

/** Scales a baseline target by a realm's difficulty and rounds it to a friendly number. */
object TargetScaler {

    /** baseline × d, rounded half-up to the unit's step, and never below one step. */
    fun scaledTarget(baseline: Double, difficulty: Double, unit: TargetUnit): Double {
        require(baseline > 0) { "baseline must be > 0, was $baseline" }
        require(difficulty > 0) { "difficulty must be > 0, was $difficulty" }
        val steps = floor(baseline * difficulty / unit.step + 0.5)
        return max(1.0, steps) * unit.step
    }

    /** Rounds [value] down to a multiple of [step], with a small tolerance for float error. */
    fun floorToStep(value: Double, step: Double): Double = floor(value / step + 1e-9) * step
}
