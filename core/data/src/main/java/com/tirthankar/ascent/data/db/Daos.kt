package com.tirthankar.ascent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.TargetUnit
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {
    @Query("SELECT * FROM player WHERE id = 0")
    fun observePlayer(): Flow<PlayerEntity?>

    @Query("SELECT * FROM player WHERE id = 0")
    suspend fun getPlayer(): PlayerEntity?

    @Upsert
    suspend fun upsertPlayer(player: PlayerEntity)

    @Query("SELECT * FROM attribute")
    fun observeAttributes(): Flow<List<AttributeEntity>>

    @Query("SELECT * FROM attribute")
    suspend fun getAttributes(): List<AttributeEntity>

    @Upsert
    suspend fun upsertAttributes(attributes: List<AttributeEntity>)

    @Query("SELECT * FROM realm_difficulty")
    fun observeDifficulties(): Flow<List<RealmDifficultyEntity>>

    @Query("SELECT * FROM realm_difficulty")
    suspend fun getDifficulties(): List<RealmDifficultyEntity>

    @Upsert
    suspend fun upsertDifficulties(difficulties: List<RealmDifficultyEntity>)

    @Query("SELECT * FROM baseline")
    suspend fun getBaselines(): List<BaselineEntity>

    @Upsert
    suspend fun upsertBaselines(baselines: List<BaselineEntity>)

    @Query("SELECT * FROM body_target")
    suspend fun getBodyTargets(): List<BodyTargetEntity>

    @Upsert
    suspend fun upsertBodyTargets(targets: List<BodyTargetEntity>)
}

/** A game day on which at least one quest of [realm] was done. */
data class RealmDay(val day: Long, val realm: Realm)

/** Completed quests linked to a goal: their count and, per unit, the sum of targets. */
data class GoalQuestTotal(
    val goalId: Long,
    val unitLabel: String,
    val done: Int,
    val targetSum: Double,
)

@Dao
interface QuestDao {
    @Insert
    suspend fun insert(quest: QuestEntity): Long

    @Insert
    suspend fun insertAll(quests: List<QuestEntity>)

    @Update
    suspend fun update(quest: QuestEntity)

    @Query("SELECT * FROM quest WHERE id = :id")
    suspend fun getById(id: Long): QuestEntity?

    /** Every quest, oldest first (the data export). */
    @Query("SELECT * FROM quest ORDER BY day, id")
    suspend fun all(): List<QuestEntity>

    /** The realm of a habit series' latest quest (v4). */
    @Query("SELECT realm FROM quest WHERE seriesId = :seriesId ORDER BY id DESC LIMIT 1")
    suspend fun realmOfSeries(seriesId: String): com.tirthankar.ascent.engine.Realm?

    /** The scope of a habit series' latest quest (v4). */
    @Query("SELECT scope FROM quest WHERE seriesId = :seriesId ORDER BY id DESC LIMIT 1")
    suspend fun scopeOfSeries(seriesId: String): QuestScope?

    @Query("SELECT * FROM quest WHERE seriesId = :seriesId ORDER BY day, id")
    suspend fun getSeries(seriesId: String): List<QuestEntity>

    @Query("SELECT * FROM quest WHERE publicId = :publicId")
    suspend fun getByPublicId(publicId: String): QuestEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM quest WHERE publicId = :publicId)")
    suspend fun publicIdExists(publicId: String): Boolean

    @Query("SELECT * FROM quest WHERE scope = 'DAILY' AND kind != 'SIDE' AND day = :day AND status != 'REMOVED' ORDER BY isDaily DESC, id")
    fun observeDay(day: Long): Flow<List<QuestEntity>>

    @Query("SELECT * FROM quest WHERE scope = 'WEEKLY' AND day = :weekStart AND status != 'REMOVED' ORDER BY id")
    fun observeWeek(weekStart: Long): Flow<List<QuestEntity>>

    @Query("SELECT * FROM quest WHERE scope = 'DAILY' AND kind != 'SIDE' AND day = :day AND status != 'REMOVED' ORDER BY isDaily DESC, id")
    suspend fun getDay(day: Long): List<QuestEntity>

    @Query("SELECT * FROM quest WHERE scope = 'WEEKLY' AND day = :weekStart AND status != 'REMOVED' ORDER BY id")
    suspend fun getWeek(weekStart: Long): List<QuestEntity>

    @Query("SELECT * FROM quest WHERE scope = 'DAILY' AND isDaily = 1 AND day = :day AND status != 'REMOVED'")
    suspend fun getDailyObjectives(day: Long): List<QuestEntity>

    @Query("UPDATE quest SET status = 'EXPIRED' WHERE scope = :scope AND kind != 'SIDE' AND day < :before AND status = 'OPEN'")
    suspend fun expireBefore(scope: QuestScope, before: Long)

    // ---- Side, penalty and emergency quests (v2) ----

    /** Side quests still open today, plus the ones finished today. */
    @Query(
        """
        SELECT * FROM quest WHERE kind = 'SIDE' AND (
            (status = 'OPEN' AND day <= :today AND expiresDay >= :today) OR (status = 'DONE' AND completedDay = :today)
        ) ORDER BY id
        """,
    )
    fun observeSide(today: Long): Flow<List<QuestEntity>>

    @Query("SELECT * FROM quest WHERE kind = 'SIDE' AND status = 'OPEN' AND day <= :today AND expiresDay >= :today ORDER BY id")
    suspend fun getOpenSide(today: Long): List<QuestEntity>

    @Query("UPDATE quest SET status = 'EXPIRED' WHERE kind = 'SIDE' AND status = 'OPEN' AND expiresDay < :today")
    suspend fun expireSide(today: Long)

    @Query("SELECT * FROM quest WHERE kind = :kind AND day = :day AND status != 'REMOVED'")
    suspend fun getKindOnDay(kind: QuestKind, day: Long): List<QuestEntity>

    @Query("SELECT COUNT(*) FROM quest WHERE kind = 'PENALTY' AND status = 'OPEN'")
    suspend fun openPenalties(): Int

    /** Quests completed between two game days (inclusive). */
    @Query("SELECT * FROM quest WHERE status = 'DONE' AND completedDay BETWEEN :from AND :to")
    suspend fun completedBetween(from: Long, to: Long): List<QuestEntity>

    /** Quests completed on game day [day], for the grind guard's counter (v4). */
    @Query("SELECT * FROM quest WHERE status = 'DONE' AND completedDay = :day ORDER BY completedAtMillis, id")
    fun observeCompletedOn(day: Long): kotlinx.coroutines.flow.Flow<List<QuestEntity>>

    @Query("SELECT * FROM quest WHERE status = 'DONE' AND completedDay BETWEEN :from AND :to")
    fun observeCompletedBetween(from: Long, to: Long): Flow<List<QuestEntity>>

    // ---- Achievement metrics (backfilled from history, D8) ----

    @Query("SELECT COALESCE(SUM(target), 0) FROM quest WHERE status = 'DONE' AND realm = :realm AND unit = :unit")
    suspend fun sumDone(realm: Realm, unit: TargetUnit): Double

    @Query("SELECT COALESCE(SUM(target), 0) FROM quest WHERE status = 'DONE' AND unit = :unit")
    suspend fun sumDoneAnyRealm(unit: TargetUnit): Double

    @Query("SELECT DISTINCT completedDay FROM quest WHERE status = 'DONE' AND templateKey = :key AND completedDay IS NOT NULL")
    suspend fun daysDone(key: String): List<Long>

    @Query("SELECT completedDay AS day, realm FROM quest WHERE status = 'DONE' AND completedDay IS NOT NULL GROUP BY completedDay, realm")
    suspend fun realmDays(): List<RealmDay>

    @Query(
        """
        SELECT goalId, unitLabel, COUNT(*) AS done, SUM(target) AS targetSum FROM quest
        WHERE goalId IS NOT NULL AND status = 'DONE'
        GROUP BY goalId, unitLabel
        """,
    )
    fun observeGoalTotals(): Flow<List<GoalQuestTotal>>
}

@Dao
interface GoalDao {
    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Query("SELECT * FROM goal WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Query("SELECT * FROM goal WHERE publicId = :publicId")
    suspend fun getByPublicId(publicId: String): GoalEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM goal")
    suspend fun maxId(): Long

    @Query("SELECT * FROM goal WHERE status = 'ACTIVE' ORDER BY horizon, id")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal WHERE status = 'ACTIVE' ORDER BY horizon, id")
    suspend fun getActive(): List<GoalEntity>

    @Query("SELECT * FROM goal WHERE horizon = 'WEEK' AND status = 'ACTIVE' AND weekStart < :weekStart")
    suspend fun getWeeklyBefore(weekStart: Long): List<GoalEntity>

    @Query("SELECT * FROM goal WHERE objectiveId IS NOT NULL")
    fun observeLinkedToObjectives(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal WHERE objectiveId IS NOT NULL")
    suspend fun getLinkedToObjectives(): List<GoalEntity>
}

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: DailySnapshotEntity)

    @Query("SELECT * FROM daily_snapshot WHERE day >= :from AND day <= :to ORDER BY day")
    suspend fun getRange(from: Long, to: Long): List<DailySnapshotEntity>

    @Query("SELECT * FROM daily_snapshot WHERE day = :day")
    suspend fun get(day: Long): DailySnapshotEntity?

    @Query("SELECT COUNT(*) FROM daily_snapshot WHERE dailyComplete = 1")
    suspend fun dailiesCompleted(): Int
}
