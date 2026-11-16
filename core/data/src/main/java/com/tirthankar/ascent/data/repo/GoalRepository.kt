package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.GoalEntity
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.GoalQuestTotal
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.model.Goal
import com.tirthankar.ascent.data.model.toModel
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.SafetyRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class NewGoal(
    val horizon: GoalHorizon,
    val realm: Realm,
    val title: String,
    val target: Double? = null,
    val unitLabel: String? = null,
    val source: QuestSource = QuestSource.MANUAL,
    /** A chapter objective this goal feeds (Phase 3). */
    val objectiveId: Long? = null,
)

@Singleton
class GoalRepository @Inject constructor(
    private val db: AscentDatabase,
    private val gameDay: GameDay,
    private val time: TimeSource,
) {
    private val goalDao = db.goalDao()

    val activeGoals: Flow<List<Goal>> =
        combine(goalDao.observeActive(), db.questDao().observeGoalTotals()) { goals, totals ->
            goals.map { it.toModel(progress(it, totals)) }
        }

    suspend fun active(): List<Goal> = activeGoals.first()

    suspend fun getByPublicId(publicId: String): Goal? =
        goalDao.getByPublicId(publicId)?.let { goal ->
            goal.toModel(progress(goal, db.questDao().observeGoalTotals().first()))
        }

    /** Adds a goal; weekly goals belong to the current week. Returns null for forbidden goals. */
    suspend fun add(goal: NewGoal): Long? {
        if (SafetyRules.isForbidden(goal.title)) return null
        val weekStart = GameClock.weekStart(gameDay.today()).toEpochDay()
        return db.withTransaction {
            val publicId = PublicIds.goalId(goalDao.maxId() + 1)
            goalDao.insert(
                GoalEntity(
                    publicId = publicId,
                    horizon = goal.horizon,
                    realm = goal.realm,
                    title = goal.title.trim(),
                    target = goal.target?.takeIf { it > 0 },
                    unitLabel = goal.unitLabel?.trim()?.takeIf { it.isNotEmpty() },
                    weekStart = if (goal.horizon == GoalHorizon.WEEK) weekStart else null,
                    deadline = null,
                    parentId = null,
                    status = GoalStatus.ACTIVE,
                    source = goal.source,
                    createdAtMillis = time.now().toEpochMilli(),
                    objectiveId = goal.objectiveId,
                ),
            )
        }
    }

    suspend fun edit(goalId: Long, title: String?, target: Double?): Boolean = db.withTransaction {
        val goal = goalDao.getById(goalId) ?: return@withTransaction false
        if (title != null && SafetyRules.isForbidden(title)) return@withTransaction false
        goalDao.update(goal.copy(title = title?.trim() ?: goal.title, target = target ?: goal.target))
        true
    }

    /** Links a goal to a chapter objective (or unlinks it with null). */
    suspend fun linkToObjective(goalId: Long, objectiveId: Long?): Boolean = db.withTransaction {
        val goal = goalDao.getById(goalId) ?: return@withTransaction false
        goalDao.update(goal.copy(objectiveId = objectiveId))
        true
    }

    suspend fun setStatus(goalId: Long, status: GoalStatus): Boolean = db.withTransaction {
        val goal = goalDao.getById(goalId) ?: return@withTransaction false
        goalDao.update(goal.copy(status = status))
        true
    }

    internal companion object {
        fun progress(goal: GoalEntity, totals: List<GoalQuestTotal>): Double {
            val mine = totals.filter { it.goalId == goal.id }
            val unit = goal.unitLabel
            val sameUnit = mine.filter { unit != null && it.unitLabel.equals(unit, ignoreCase = true) }
            return if (sameUnit.isNotEmpty()) sameUnit.sumOf { it.targetSum } else mine.sumOf { it.done }.toDouble()
        }
    }
}
