package com.tirthankar.ascent.feature.status

import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.StatBlock

/** Static sample data for Phase 1 and for previews. Replaced by Room data in Phase 2. */
object SamplePlayer {
    val snapshot = PlayerSnapshot(
        name = "Player",
        title = "The Unmeasured",
        rank = Rank.E,
        totalXp = 9_000, // level 10, 1,309 / 1,995 into it
        hp = 180,
        mp = 120,
        stats = StatBlock(str = 12, agi = 11, int = 15, foc = 13, wis = 10, vit = 9),
        streakDays = 12,
    )
}
