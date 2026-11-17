package com.tirthankar.ascent.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.tirthankar.ascent.engine.SeriesPeriod

// ---- Room v4 (Phase 5, PHASE5.md section 4) ----

/**
 * One period of one habit series: [periodKey] is the epoch day of the game day, or of
 * the week's Sunday for weekly series. [x] = min(progress / target, 1). Periods that
 * weren't issued, and neutral restore days, have no row.
 */
@Entity(tableName = "habit_observation", primaryKeys = ["seriesId", "periodKey"])
data class HabitObservationEntity(
    val seriesId: String,
    val periodKey: Long,
    val x: Double,
    val target: Double,
    val progress: Double,
    val recordedAt: Long,
)

/** The latest strength per series, recomputed from the observations on a miss or an edit. */
@Entity(tableName = "habit_series_cache")
data class HabitSeriesCacheEntity(
    @PrimaryKey val seriesId: String,
    val period: SeriesPeriod,
    val strength: Double,
    val lastPeriodKey: Long,
    /** Current streak of fully done periods. */
    val streak: Int,
    /** Best streak of fully done periods. */
    val best: Int,
    val observations: Int,
    /** The first observed period, for "measuring since". */
    val sinceKey: Long?,
)

/** What paid the XP in an [XpEventEntity]. An undo logs a negative amount from the same source. */
enum class XpSource { QUEST, BOSS_TASK, BOSS, TRIAL, GATE, RANK_TRIAL, CHAPTER, UNBROKEN, OTHER, PLAN_WRITTEN, PLAN_COMPLETED }

/**
 * Every XP grant from v4 on (for the export's xp_log.csv). [amount] is negative for an
 * undo. [multipliers] is a short readable list, e.g. "guard=0.5,power=3.0,crit=2.0".
 */
@Entity(tableName = "xp_event", indices = [Index("day")])
data class XpEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** The game day. */
    val day: Long,
    val source: XpSource,
    val amount: Long,
    val multipliers: String = "",
    /** The quest, boss task, Gate or other row it came from, when there is one. */
    val refId: Long? = null,
)

@Dao
interface HabitDao {

    @Upsert
    suspend fun upsertObservation(observation: HabitObservationEntity)

    @Query("DELETE FROM habit_observation WHERE seriesId = :seriesId AND periodKey = :periodKey")
    suspend fun deleteObservation(seriesId: String, periodKey: Long)

    @Query("SELECT * FROM habit_observation WHERE seriesId = :seriesId ORDER BY periodKey")
    suspend fun observations(seriesId: String): List<HabitObservationEntity>

    @Query("SELECT * FROM habit_observation ORDER BY seriesId, periodKey")
    suspend fun allObservations(): List<HabitObservationEntity>

    @Query("SELECT DISTINCT seriesId FROM habit_observation")
    suspend fun seriesIds(): List<String>

    @Upsert
    suspend fun upsertCache(cache: HabitSeriesCacheEntity)

    @Query("SELECT * FROM habit_series_cache WHERE seriesId = :seriesId")
    suspend fun cache(seriesId: String): HabitSeriesCacheEntity?

    @Query("SELECT * FROM habit_series_cache")
    suspend fun allCache(): List<HabitSeriesCacheEntity>

    @Query("SELECT * FROM habit_series_cache")
    fun observeCache(): kotlinx.coroutines.flow.Flow<List<HabitSeriesCacheEntity>>

    @Query("DELETE FROM habit_series_cache WHERE seriesId = :seriesId")
    suspend fun invalidate(seriesId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertXpEvent(event: XpEventEntity): Long

    @Query("SELECT * FROM xp_event ORDER BY timestamp, id")
    suspend fun xpEvents(): List<XpEventEntity>
}
