package com.tirthankar.ascent.data.db

import androidx.room.*

@Entity(tableName = "study_session", indices = [Index("day"), Index("questId")])
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val subject: String,
    val objective: String,
    val plannedSeconds: Long,
    val quoteIndex: Int,
    val questId: Long? = null,
    val taskSessionId: String? = null,
    val state: String = "RUNNING",
    val startedAt: Long,
    val endsAt: Long,
    val activeSeconds: Long = 0,
    val anchor: Long? = null,
    val boot: Int,
    val endedAt: Long? = null,
    val learned: String = "",
    val nextStep: String = "",
    val xpAwarded: Long = 0,
    val questXpAwarded: Long = 0,
)

@Dao
interface StudyDao {
    @Query("SELECT * FROM study_session ORDER BY startedAt DESC, id DESC")
    suspend fun all(): List<StudySessionEntity>
    @Query("SELECT * FROM study_session WHERE id = :id")
    suspend fun get(id: Long): StudySessionEntity?
    @Query("SELECT * FROM study_session WHERE state IN ('RUNNING', 'RESULT') ORDER BY id DESC LIMIT 1")
    suspend fun active(): StudySessionEntity?
    @Query("SELECT COUNT(*) FROM study_session WHERE day = :day")
    suspend fun attempts(day: Long): Int
    @Insert suspend fun insert(session: StudySessionEntity): Long
    @Update suspend fun update(session: StudySessionEntity)
}
