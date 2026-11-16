package com.tirthankar.ascent.engine

/** Fatigue (SPEC override): HP below 30% → every realm × 0.7, three objectives, one of them recovery. */
object Fatigue {

    const val HP_FRACTION = 0.30
    const val MULTIPLIER = 0.7
    const val OBJECTIVES = 3

    fun isFatigued(hp: Int, maxHp: Int): Boolean = maxHp > 0 && hp < HP_FRACTION * maxHp
}

/** Re-entry (SPEC override): after 3+ missed days every multiplier restarts at 0.8 × its value. */
object ReEntry {

    const val MISSED_DAYS = 3
    const val FACTOR = 0.8

    fun apply(states: Map<Realm, RealmDifficulty>): Map<Realm, RealmDifficulty> =
        Realm.entries.associateWith { realm ->
            val state = states[realm] ?: RealmDifficulty()
            state.copy(
                multiplier = (state.multiplier * FACTOR).coerceIn(
                    DifficultyController.MIN_MULTIPLIER,
                    DifficultyController.MAX_MULTIPLIER,
                ),
            )
        }
}
