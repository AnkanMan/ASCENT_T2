package com.tirthankar.ascent.engine

import kotlin.math.exp

/** One realm's controller state: multiplier d and completion moving average c. */
data class RealmDifficulty(
    val multiplier: Double = DifficultyController.INITIAL_MULTIPLIER,
    val completionEma: Double = DifficultyController.TARGET_COMPLETION,
)

/**
 * Per-realm adaptive difficulty (SPEC "Adaptive difficulty engine"). Daily, with x the
 * fraction of that realm's objectives completed:
 *   c ← (1 − α)·c + α·x,   d ← d · exp[η·(c − c*)],   d clamped to [0.5, 3.0].
 *
 * Safety overrides (Fatigue, Body caps, deload, re-entry, floors) sit above this
 * and arrive in Phase 2.
 */
object DifficultyController {

    const val ALPHA = 0.3
    const val TARGET_COMPLETION = 0.80
    const val ETA = 0.15
    const val MIN_MULTIPLIER = 0.5
    const val MAX_MULTIPLIER = 3.0
    const val INITIAL_MULTIPLIER = 1.0

    /** Applies one day with completion fraction [completion] in [0, 1]. */
    fun update(state: RealmDifficulty, completion: Double): RealmDifficulty {
        require(completion in 0.0..1.0) { "completion must be in [0, 1], was $completion" }
        val ema = (1 - ALPHA) * state.completionEma + ALPHA * completion
        val multiplier = (state.multiplier * exp(ETA * (ema - TARGET_COMPLETION)))
            .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        return RealmDifficulty(multiplier = multiplier, completionEma = ema)
    }

    /** Updates each realm that had objectives today; realms missing from [completions] are unchanged. */
    fun updateAll(
        states: Map<Realm, RealmDifficulty>,
        completions: Map<Realm, Double>,
    ): Map<Realm, RealmDifficulty> =
        Realm.entries.associateWith { realm ->
            val state = states[realm] ?: RealmDifficulty()
            completions[realm]?.let { update(state, it) } ?: state
        }
}
