package com.tirthankar.ascent.engine

/**
 * Player ranks in order. Reaching [minLevel] unlocks that rank's Rank Trial;
 * the rank itself is earned by clearing the trial (SPEC).
 * [SIGMA] is hidden: it needs level 100 plus the thesis raid, so level alone never unlocks it.
 */
enum class Rank(val minLevel: Int?) {
    E(1),
    D(10),
    C(25),
    B(45),
    A(70),
    S(100),
    SIGMA(null),
}

object RankRules {

    /** Highest rank whose level threshold [level] meets. Never [Rank.SIGMA]. */
    fun rankUnlockedBy(level: Int): Rank {
        require(level >= 1) { "level must be >= 1, was $level" }
        return Rank.entries.last { it.minLevel != null && level >= it.minLevel }
    }

    /**
     * The Rank Trial open to a player at [currentRank] and [level], or null.
     * Trials go one rank at a time, so an E-rank player at level 30 faces the D trial first.
     */
    fun availableTrial(currentRank: Rank, level: Int): Rank? {
        require(level >= 1) { "level must be >= 1, was $level" }
        val next = Rank.entries.getOrNull(currentRank.ordinal + 1) ?: return null
        val threshold = next.minLevel ?: return null
        return if (level >= threshold) next else null
    }
}
