package com.tirthankar.ascent.engine

import kotlin.math.max

/** Baseline quantities that template targets scale from (d = 1.0). */
enum class BaselineKey(val unit: TargetUnit) {
    PUSH_UPS(TargetUnit.REPS),
    SQUATS(TargetUnit.REPS),
    PLANK(TargetUnit.SECONDS),
    STEPS(TargetUnit.STEPS),
    WALK(TargetUnit.MINUTES),
    DEEP_WORK(TargetUnit.MINUTES),
    READING(TargetUnit.PAGES),
}

/** What the Awakening measured (current values only; goals live in [AwakeningMeasurements]). */
data class BaselineInputs(
    val maxPushUps: Int? = null,
    val maxSquats: Int? = null,
    val maxPlankSeconds: Int? = null,
    val averageSteps: Int? = null,
    val deepWorkHoursPerDay: Double? = null,
    val pagesPerWeek: Int? = null,
)

/** Baseline targets (SPEC "Adaptive difficulty engine", D6). */
object Baselines {

    const val WALK_MINUTES = 20.0
    const val DEEP_WORK_MINUTES = 60.0
    const val LONG_DEEP_WORK_MINUTES = 90.0
    const val LONG_DEEP_WORK_THRESHOLD_HOURS = 4.0
    const val MIN_READING_PAGES = 10.0
    const val EXTRA_STEPS = 1_000.0

    /** Used when a measurement was skipped. */
    val DEFAULTS: Map<BaselineKey, Double> = mapOf(
        BaselineKey.PUSH_UPS to 20.0,
        BaselineKey.SQUATS to 30.0,
        BaselineKey.PLANK to 60.0,
        BaselineKey.STEPS to 6_000.0,
        BaselineKey.WALK to WALK_MINUTES,
        BaselineKey.DEEP_WORK to DEEP_WORK_MINUTES,
        BaselineKey.READING to MIN_READING_PAGES,
    )

    fun fromInputs(inputs: BaselineInputs): Map<BaselineKey, Double> {
        val measured = buildMap {
            inputs.maxPushUps?.takeIf { it > 0 }?.let { put(BaselineKey.PUSH_UPS, 2.0 * it) }
            inputs.maxSquats?.takeIf { it > 0 }?.let { put(BaselineKey.SQUATS, 2.0 * it) }
            inputs.maxPlankSeconds?.takeIf { it > 0 }?.let { put(BaselineKey.PLANK, 2.0 * it) }
            inputs.averageSteps?.takeIf { it >= 0 }?.let { put(BaselineKey.STEPS, it + EXTRA_STEPS) }
            put(BaselineKey.WALK, WALK_MINUTES)
            inputs.deepWorkHoursPerDay?.let {
                put(
                    BaselineKey.DEEP_WORK,
                    if (it > LONG_DEEP_WORK_THRESHOLD_HOURS) LONG_DEEP_WORK_MINUTES else DEEP_WORK_MINUTES,
                )
            }
            inputs.pagesPerWeek?.let { put(BaselineKey.READING, max(MIN_READING_PAGES, it / 7.0)) }
        }
        return DEFAULTS + measured
    }
}
