package com.tirthankar.ascent.feature.status

import com.tirthankar.ascent.data.model.PlayerProfile
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.StatBlock
import com.tirthankar.ascent.engine.XpCurve

/** The stored player state the Status Window needs. */
data class PlayerSnapshot(
    val name: String,
    val title: String,
    val rank: Rank,
    val totalXp: Long,
    val hp: Int,
    val mp: Int,
    val stats: StatBlock,
    val streakDays: Int,
    val level: Int = XpCurve.levelProgress(totalXp).level,
    val freePoints: Int = 0,
)

fun PlayerProfile.toSnapshot() = PlayerSnapshot(
    name = name,
    title = title,
    rank = rank,
    totalXp = totalXp,
    hp = hp,
    mp = mp,
    stats = stats,
    streakDays = streakDays,
    level = level,
    freePoints = freePoints,
)

/** Turns stored state into display state using the engine for every derived number. */
object StatusUiStateMapper {

    /** [pending] are free points placed but not yet confirmed; max HP and MP preview them. */
    fun map(player: PlayerSnapshot, pending: Map<Stat, Int> = emptyMap()): StatusUiState {
        val progress = XpCurve.levelProgress(player.totalXp)
        val previewStats = with(player.stats) {
            fun v(stat: Stat) = get(stat) + (pending[stat] ?: 0)
            StatBlock(v(Stat.STR), v(Stat.AGI), v(Stat.INT), v(Stat.FOC), v(Stat.WIS), v(Stat.VIT))
        }
        val maxHp = DerivedStats.maxHp(previewStats)
        val maxMp = DerivedStats.maxMp(previewStats)
        // A level is never lost: show the stored level even if XP were ever below it.
        val level = maxOf(player.level, progress.level)
        val sameLevel = level == progress.level
        return StatusUiState(
            name = player.name,
            title = player.title,
            rank = player.rank,
            level = level,
            xpIntoLevel = if (sameLevel) progress.xpIntoLevel else 0,
            xpForNext = if (sameLevel) progress.xpForNext else XpCurve.xpToNext(level),
            hp = player.hp.coerceIn(0, maxHp),
            maxHp = maxHp,
            mp = player.mp.coerceIn(0, maxMp),
            maxMp = maxMp,
            stats = Stat.entries.map { StatLine(it, player.stats[it], pending[it] ?: 0) },
            streakDays = player.streakDays,
            freePoints = (player.freePoints - pending.values.sum()).coerceAtLeast(0),
        )
    }
}
