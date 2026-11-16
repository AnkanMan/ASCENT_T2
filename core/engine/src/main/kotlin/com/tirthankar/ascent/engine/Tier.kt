package com.tirthankar.ascent.engine

/** Quest difficulty tier. [baseXp] is B_tier in the quest XP formula (SPEC). */
enum class Tier(val baseXp: Int) {
    TRIVIAL(20),
    EASY(40),
    NORMAL(80),
    HARD(150),
    EXTREME(300),
}
