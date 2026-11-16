package com.tirthankar.ascent.data.repo

import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.db.XpEventEntity
import com.tirthankar.ascent.data.db.XpSource
import com.tirthankar.ascent.engine.Leveling

/**
 * Where an XP change came from, for the XP log (Phase 5, `xp_event`): the game [day],
 * the time [at], the row it came from and the multipliers applied, e.g.
 * "guard=0.5 power=3.0 crit=2.0".
 */
internal data class XpLog(
    val source: XpSource,
    val day: Long,
    val at: Long,
    val refId: Long? = null,
    val multipliers: String = "",
)

/** Writes one XP event. [amount] is negative for an undo. Zero amounts aren't logged. */
internal suspend fun AscentDatabase.logXp(amount: Long, log: XpLog) {
    if (amount == 0L) return
    habitDao().insertXpEvent(
        XpEventEntity(
            timestamp = log.at,
            day = log.day,
            source = log.source,
            amount = amount,
            multipliers = log.multipliers,
            refId = log.refId,
        ),
    )
}

/** "name=value" pairs for the multipliers that aren't 1, space-separated. */
internal fun multipliers(vararg pairs: Pair<String, Double>): String =
    pairs.filter { (_, v) -> v != 1.0 }.joinToString(" ") { (k, v) -> "$k=$v" }

/**
 * Adds XP from a chapter, Gate, boss or Trial, with level-ups, free and skill points,
 * and logs it. Call inside a transaction; the level-up event goes to [emitted] for
 * after it commits.
 */
internal suspend fun AscentDatabase.grantXp(xp: Long, emitted: MutableList<GameEvent>, log: XpLog): PlayerEntity? {
    val dao = playerDao()
    val player = dao.getPlayer() ?: return null
    if (xp <= 0) return player
    val totalXp = player.totalXp + xp
    val levels = Leveling.change(player.level, totalXp)
    val updated = player.copy(
        totalXp = totalXp,
        level = levels.toLevel,
        freePoints = player.freePoints + levels.freePoints,
        skillPoints = player.skillPoints + levels.skillPoints,
    )
    dao.upsertPlayer(updated)
    logXp(xp, log)
    if (levels.leveledUp) emitted += GameEvent.LevelUp(levels.fromLevel, levels.toLevel, levels.freePoints)
    return updated
}

/**
 * Takes back XP from an undone completion, logged as a negative amount from the same
 * source. Never lowers the level (CLAUDE.md): undo is only offered while the level
 * hasn't changed since.
 */
internal suspend fun AscentDatabase.takeXp(xp: Long, log: XpLog) {
    val dao = playerDao()
    val player = dao.getPlayer() ?: return
    dao.upsertPlayer(player.copy(totalXp = (player.totalXp - xp).coerceAtLeast(0)))
    logXp(-xp, log)
}
