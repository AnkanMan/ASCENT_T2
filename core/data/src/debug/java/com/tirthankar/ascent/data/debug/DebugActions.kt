package com.tirthankar.ascent.data.debug

import androidx.room.withTransaction
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.repo.RankTrialRepository
import com.tirthankar.ascent.data.repo.SkillRepository
import com.tirthankar.ascent.data.repo.toStatBlock
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.XpCurve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the debug panel shows about the player. */
data class DebugSnapshot(
    val level: Int,
    val rank: Rank,
    val mp: Int,
    val maxMp: Int,
    val stats: Map<Stat, Int>,
    val freePoints: Int,
    val essence: Int,
    val respecTokens: Int,
    val classId: String?,
    val trialOpen: Boolean,
)

/**
 * Debug builds only (PHASE4.md section 9): direct edits for testing classes, trials and
 * evolutions without waiting months. This source set is absent from release builds.
 * Levels only go up, even here: the player never loses a level.
 */
@Singleton
class DebugActions @Inject constructor(
    private val db: AscentDatabase,
    private val skills: SkillRepository,
    private val trials: RankTrialRepository,
) {
    private val playerDao = db.playerDao()

    val snapshot: Flow<DebugSnapshot?> = combine(
        playerDao.observePlayer(),
        playerDao.observeAttributes(),
        trials.open.map { it != null },
    ) { player, attributes, trialOpen ->
        player ?: return@combine null
        DebugSnapshot(
            level = player.level,
            rank = player.rank,
            mp = player.mp,
            maxMp = DerivedStats.maxMp(attributes.toStatBlock()),
            stats = attributes.associate { it.stat to it.value },
            freePoints = player.freePoints,
            essence = player.essence,
            respecTokens = player.respecTokens,
            classId = player.classId,
            trialOpen = trialOpen,
        )
    }

    /** Unspent skill points, including granted ones. */
    suspend fun skillPoints(): Int = skills.unspentPoints()

    suspend fun raiseLevel(by: Int) = update {
        val level = it.level + by.coerceAtLeast(0)
        it.copy(level = level, totalXp = maxOf(it.totalXp, XpCurve.totalXpToReach(level)))
    }

    suspend fun setRank(rank: Rank) = update { it.copy(rank = rank) }

    suspend fun shiftStat(stat: Stat, by: Int) = db.withTransaction {
        playerDao.upsertAttributes(
            playerDao.getAttributes().filter { it.stat == stat }.map { it.copy(value = (it.value + by).coerceAtLeast(1)) },
        )
    }

    suspend fun fillMp() = db.withTransaction {
        val max = DerivedStats.maxMp(playerDao.getAttributes().toStatBlock())
        playerDao.getPlayer()?.let { playerDao.upsertPlayer(it.copy(mp = max)) }
    }

    suspend fun emptyMp() = update { it.copy(mp = 0) }

    suspend fun grantSkillPoint() = update { it.copy(bonusSkillPoints = it.bonusSkillPoints + 1) }

    suspend fun grantFreePoints(n: Int) = update { it.copy(freePoints = it.freePoints + n) }

    suspend fun grantEssence(n: Int) = update { it.copy(essence = it.essence + n) }

    suspend fun grantRespecToken() = update { it.copy(respecTokens = it.respecTokens + 1) }

    /** Offers the rank-up class re-choice again (A14), for players with a class. */
    suspend fun offerClassChoice() = update { it.copy(classRechoice = it.classId != null) }

    /** Fills every objective of the open rank trial, which then passes. */
    suspend fun clearRankTrial() {
        db.withTransaction {
            val dao = db.gateDao()
            dao.openTrials().forEach { trial ->
                dao.objectives(trial.id).forEach { dao.updateObjective(it.copy(logged = it.target)) }
            }
        }
        trials.closeCleared()
    }

    private suspend fun update(change: (PlayerEntity) -> PlayerEntity) = db.withTransaction {
        playerDao.getPlayer()?.let { playerDao.upsertPlayer(change(it)) }
    }
}
