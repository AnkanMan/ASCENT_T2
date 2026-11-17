package com.tirthankar.ascent.engine

enum class MeasurementMode { CHECKBOX, COUNT, DURATION, QUANTITY }

/** Shared rules for planner rewards and recorded task results. */
object PlannerRules {
    const val WRITING_XP = 10L
    const val COMPLETION_XP = 40L

    fun allDone(items: List<Boolean>): Boolean = items.isNotEmpty() && items.all { it }

    fun mode(unit: TargetUnit): MeasurementMode = when (unit) {
        TargetUnit.REPS, TargetUnit.STEPS, TargetUnit.PAGES -> MeasurementMode.COUNT
        TargetUnit.MINUTES, TargetUnit.SECONDS, TargetUnit.HOURS -> MeasurementMode.DURATION
        TargetUnit.KM -> MeasurementMode.QUANTITY
        TargetUnit.OTHER -> MeasurementMode.CHECKBOX
    }

    fun validResult(value: Double, mode: MeasurementMode): Boolean =
        value.isFinite() && value >= 0 && (mode != MeasurementMode.COUNT || value == kotlin.math.floor(value))

    fun quantity(seconds: Long, unit: TargetUnit): Double = when (unit) {
        TargetUnit.HOURS -> seconds / 3600.0
        TargetUnit.MINUTES -> seconds / 60.0
        else -> seconds.toDouble()
    }

    /** Monotonic time is usable only in the same boot; invalid anchors add nothing. */
    fun elapsed(saved: Long, anchor: Long?, boot: Int?, now: Long, currentBoot: Int): Long =
        saved + if (anchor != null && boot == currentBoot && now >= anchor) (now - anchor) / 1000 else 0
}
