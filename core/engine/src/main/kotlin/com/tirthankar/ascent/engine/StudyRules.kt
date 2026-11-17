package com.tirthankar.ascent.engine

/** Study rewards depend only on an eligible completed attempt's measured time. */
object StudyRules {
    const val MIN_SECONDS = 30L * 60
    const val MAX_ATTEMPTS = 3
    const val QUOTE_COUNT = 12

    fun validDuration(seconds: Long, linkedSeconds: Long = 0): Boolean =
        seconds >= maxOf(MIN_SECONDS, linkedSeconds)

    fun reward(elapsed: Long, target: Long): Long = when {
        !validDuration(target) || elapsed < target -> 0
        elapsed > 3600 -> 100
        else -> 30
    }

    /** Uniform draw from all quotes except the preceding selection. */
    fun quote(previous: Int?, draw: Int): Int {
        if (previous == null || previous !in 0 until QUOTE_COUNT) return Math.floorMod(draw, QUOTE_COUNT)
        val candidate = Math.floorMod(draw, QUOTE_COUNT - 1)
        return if (candidate >= previous) candidate + 1 else candidate
    }
}
