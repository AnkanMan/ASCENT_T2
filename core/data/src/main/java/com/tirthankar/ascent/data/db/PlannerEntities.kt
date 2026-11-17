package com.tirthankar.ascent.data.db

import androidx.room.*

@Entity(tableName = "day_plan", indices = [Index(value = ["targetDay"], unique = true)])
data class DayPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetDay: Long,
    val state: String = "SCHEDULED",
    val createdAt: Long,
    val writtenAt: Long,
    val completedAt: Long? = null,
)

@Entity(tableName = "plan_item", indices = [Index("planId"), Index(value = ["planId", "questId"], unique = true)])
data class PlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val position: Int,
    val title: String,
    val questId: Long? = null,
    val mode: String = "CHECKBOX",
    val target: Double = 1.0,
    val unit: String = "done",
    val progress: Double = 0.0,
    val done: Boolean = false,
)

@Entity(tableName = "task_session", indices = [Index("questId"), Index("itemId")])
data class TaskSessionEntity(
    @PrimaryKey val id: String,
    val questId: Long? = null,
    val itemId: Long? = null,
    val state: String = "PAUSED",
    val startedAt: Long,
    val endsAt: Long,
    val activeSeconds: Long = 0,
    val anchor: Long? = null,
    val boot: Int? = null,
    val endedAt: Long? = null,
    val quantity: Double = 0.0,
    val origin: String = "TIMER",
    val note: String = "",
)

@Dao
interface PlannerDao {
    @Query("SELECT * FROM day_plan ORDER BY targetDay DESC") suspend fun plans(): List<DayPlanEntity>
    @Query("SELECT * FROM day_plan WHERE targetDay = :day") suspend fun plan(day: Long): DayPlanEntity?
    @Query("SELECT * FROM day_plan WHERE id = :id") suspend fun planById(id: Long): DayPlanEntity?
    @Insert suspend fun insert(plan: DayPlanEntity): Long
    @Update suspend fun update(plan: DayPlanEntity)
    @Query("SELECT * FROM plan_item ORDER BY planId, position") suspend fun items(): List<PlanItemEntity>
    @Query("SELECT * FROM plan_item WHERE planId = :id ORDER BY position") suspend fun items(id: Long): List<PlanItemEntity>
    @Query("SELECT * FROM plan_item WHERE id = :id") suspend fun item(id: Long): PlanItemEntity?
    @Insert suspend fun insert(item: PlanItemEntity): Long
    @Update suspend fun update(item: PlanItemEntity)
    @Query("DELETE FROM plan_item WHERE planId = :id") suspend fun deleteItems(id: Long)
    @Query("SELECT COUNT(*) FROM plan_item i JOIN day_plan p ON p.id = i.planId WHERE i.questId = :id AND p.completedAt IS NOT NULL")
    suspend fun sealedLinks(id: Long): Int
    @Query("SELECT * FROM task_session ORDER BY startedAt DESC, id") suspend fun sessions(): List<TaskSessionEntity>
    @Query("SELECT * FROM task_session WHERE id = :id") suspend fun session(id: String): TaskSessionEntity?
    @Insert suspend fun insert(session: TaskSessionEntity)
    @Update suspend fun update(session: TaskSessionEntity)
    @Query("UPDATE task_session SET state = 'VOID', anchor = NULL WHERE questId = :id AND state IN ('RUNNING', 'PAUSED', 'RESULT')")
    suspend fun cancelQuestSessions(id: Long)
}
