package com.tirthankar.ascent.data.repo

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.tirthankar.ascent.data.*
import com.tirthankar.ascent.data.db.*
import com.tirthankar.ascent.engine.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

open class SessionClock @Inject constructor(@param:ApplicationContext private val context: Context) {
    open fun elapsed(): Long = SystemClock.elapsedRealtime()
    open fun boot(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
}

data class PlannedTask(val title: String, val mode: String = "CHECKBOX", val target: Double = 1.0, val unit: String = "done")
data class ActivitySnapshot(
    val today: Long,
    val plans: List<DayPlanEntity>,
    val items: List<PlanItemEntity>,
    val quests: List<QuestEntity>,
    val sessions: List<TaskSessionEntity>,
    val now: Long,
    val nextReset: Long,
    val studyActive: Boolean = false,
    val studyQuestId: Long? = null,
)

@Singleton
class ActivityRepository @Inject constructor(
    private val db: AscentDatabase,
    private val day: GameDay,
    private val clock: SessionClock,
    private val coordinator: PlannerCoordinator,
    private val quests: QuestRepository,
    private val events: GameEvents,
    private val skills: SkillRepository,
) {
    private val dao = db.plannerDao()

    suspend fun snapshot(): ActivitySnapshot = db.withTransaction {
        coordinator.reconcile()
        reconcileStudy(db, day, clock)
        // A boot change invalidates the monotonic anchor; keep only safely saved time.
        dao.sessions().filter { it.state == "RUNNING" && (it.boot != clock.boot() || (it.anchor ?: 0) > clock.elapsed()) }.forEach {
            dao.update(it.copy(state = "PAUSED", anchor = null, note = "CLOCK_RECOVERY"))
        }
        dao.sessions().filter { it.state == "RUNNING" && seconds(it) - it.activeSeconds >= 5 }.forEach {
            val delta = seconds(it) - it.activeSeconds
            dao.update(it.copy(activeSeconds = it.activeSeconds + delta, anchor = (it.anchor ?: clock.elapsed()) + delta * 1000))
        }
        ActivitySnapshot(day.today().toEpochDay(), dao.plans(), dao.items(), db.questDao().all(), dao.sessions(), day.now().toEpochMilli(), day.nextReset().toEpochMilli(), db.studyDao().active() != null, db.studyDao().active()?.questId)
    }

    suspend fun saveTomorrow(targetDay: Long, items: List<PlannedTask>): Boolean {
        val emitted = mutableListOf<GameEvent>()
        val saved = db.withTransaction {
            if (targetDay != day.today().toEpochDay() + 1 || items.isEmpty() || db.playerDao().getPlayer() == null) return@withTransaction false
            if (items.any { it.title.isBlank() || SafetyRules.isForbidden(it.title) || !it.target.isFinite() || it.target <= 0 || it.mode !in MeasurementMode.entries.map { m -> m.name } || (it.mode == "DURATION" && TargetUnit.fromLabel(it.unit) !in listOf(TargetUnit.SECONDS, TargetUnit.MINUTES, TargetUnit.HOURS)) || (it.mode == "COUNT" && it.target != kotlin.math.floor(it.target)) }) return@withTransaction false
            val now = day.now().toEpochMilli()
            val old = dao.plan(targetDay)
            if (old != null && old.state != "SCHEDULED") return@withTransaction false
            val id = old?.id ?: dao.insert(DayPlanEntity(targetDay = targetDay, createdAt = now, writtenAt = now))
            dao.deleteItems(id)
            items.forEachIndexed { index, item -> dao.insert(PlanItemEntity(planId = id, position = index, title = item.title.trim(), mode = item.mode, target = item.target, unit = item.unit.trim())) }
            if (old == null) db.grantXp(PlannerRules.WRITING_XP, emitted, XpLog(XpSource.PLAN_WRITTEN, day.today().toEpochDay(), now, id))
            true
        }
        emitted.forEach(events::emit)
        return saved
    }

    suspend fun bind(itemId: Long, questId: Long): Boolean = db.withTransaction {
        val item = dao.item(itemId) ?: return@withTransaction false
        val plan = dao.planById(item.planId) ?: return@withTransaction false
        val quest = db.questDao().getById(questId) ?: return@withTransaction false
        if (plan.targetDay != day.today().toEpochDay() || plan.completedAt != null || item.questId != null || item.progress != 0.0 || item.done || dao.sessions().any { it.itemId == itemId }) return@withTransaction false
        if (quest.day != plan.targetDay || quest.scope != QuestScope.DAILY || quest.kind != QuestKind.NORMAL || quest.status != QuestStatus.OPEN || dao.items(plan.id).any { it.questId == questId }) return@withTransaction false
        dao.update(item.copy(questId = questId))
        true
    }

    suspend fun setMode(questId: Long, mode: String): Boolean = db.withTransaction {
        val q = db.questDao().getById(questId) ?: return@withTransaction false
        if (PlannerRules.singleSessionFocus(q.templateKey, q.scope == QuestScope.DAILY) && mode != "DURATION") return@withTransaction false
        if (mode !in MeasurementMode.entries.map { it.name } || q.status != QuestStatus.OPEN || q.actualProgress != 0.0 || dao.sessions().any { it.questId == questId }) return@withTransaction false
        if (mode == "DURATION" && q.unit !in listOf(TargetUnit.SECONDS, TargetUnit.MINUTES, TargetUnit.HOURS)) return@withTransaction false
        db.questDao().update(q.copy(measurementMode = mode))
        true
    }

    private suspend fun deadline(questId: Long?, itemId: Long?): Long? {
        if ((questId == null) == (itemId == null)) return null
        val today = day.today()
        if (questId != null) {
            val q = db.questDao().getById(questId) ?: return null
            if (q.kind != QuestKind.NORMAL || q.status != QuestStatus.OPEN) return null
            val start = LocalDate.ofEpochDay(q.day)
            if (q.day != (if (q.scope == QuestScope.DAILY) today else GameClock.weekStart(today)).toEpochDay()) return null
            return day.clock().dayStart(start.plusDays(if (q.scope == QuestScope.DAILY) 1 else 7)).toEpochMilli()
        }
        val item = dao.item(itemId!!) ?: return null
        val plan = dao.planById(item.planId) ?: return null
        if (item.questId != null || item.done || plan.completedAt != null || plan.targetDay != today.toEpochDay()) return null
        return day.nextReset().toEpochMilli()
    }

    suspend fun start(questId: Long? = null, itemId: Long? = null, manual: Boolean = false): String? = db.withTransaction {
        coordinator.reconcile()
        reconcileStudy(db, day, clock)
        if (db.studyDao().active() != null) return@withTransaction null
        val end = deadline(questId, itemId) ?: return@withTransaction null
        if (db.skillDao().activeCasts().any { cast -> cast.note == null && skills.definition(cast.skillId)?.handler == PowerHandlers.HYPERFOCUS }) return@withTransaction null
        val existing = dao.sessions().firstOrNull { it.questId == questId && it.itemId == itemId && it.state in listOf("RUNNING", "PAUSED", "RESULT") }
        if (existing != null) return@withTransaction existing.id
        if (!manual && hasRunningFocus()) return@withTransaction null
        if (!manual) dao.sessions().filter { it.state == "RUNNING" }.forEach { dao.update(paused(it)) }
        val id = UUID.randomUUID().toString()
        dao.insert(TaskSessionEntity(id, questId, itemId, if (manual) "RESULT" else "RUNNING", day.now().toEpochMilli(), end,
            anchor = if (manual) null else clock.elapsed(), boot = clock.boot(), origin = if (manual) "MANUAL" else "TIMER"))
        id
    }

    private suspend fun hasRunningFocus(exceptId: String? = null): Boolean = dao.sessions().any { session ->
        session.state == "RUNNING" && session.id != exceptId && session.questId?.let { id ->
            db.questDao().getById(id)?.let { PlannerRules.singleSessionFocus(it.templateKey, it.scope == QuestScope.DAILY) }
        } == true
    }

    fun seconds(session: TaskSessionEntity): Long = PlannerRules.elapsed(session.activeSeconds, session.anchor.takeIf { session.state == "RUNNING" }, session.boot, clock.elapsed(), clock.boot())
    private fun paused(s: TaskSessionEntity) = s.copy(state = "PAUSED", activeSeconds = seconds(s), anchor = null)

    suspend fun control(id: String, action: String): Boolean = db.withTransaction {
        coordinator.reconcile()
        reconcileStudy(db, day, clock)
        val study = db.studyDao().active()
        if (study?.taskSessionId == id || (action == "RESUME" && study != null)) return@withTransaction false
        val s = dao.session(id) ?: return@withTransaction false
        if (s.state !in listOf("RUNNING", "PAUSED", "RESULT") || deadline(s.questId, s.itemId) == null) return@withTransaction false
        val quest = s.questId?.let { db.questDao().getById(it) }
        if (action in listOf("PAUSE", "RESUME") && quest?.let { PlannerRules.singleSessionFocus(it.templateKey, it.scope == QuestScope.DAILY) } == true) return@withTransaction false
        if (action == "RESUME" && hasRunningFocus(exceptId = id)) return@withTransaction false
        if (action == "RESUME" && db.skillDao().activeCasts().any { it.note == null && skills.definition(it.skillId)?.handler == PowerHandlers.HYPERFOCUS }) return@withTransaction false
        val next = when (action) {
            "PAUSE" -> paused(s)
            "FINISH" -> paused(s).copy(state = "RESULT")
            "DISCARD" -> paused(s).copy(state = "VOID", endedAt = day.now().toEpochMilli())
            "RESUME" -> {
                dao.sessions().filter { it.state == "RUNNING" && it.id != id }.forEach { dao.update(paused(it)) }
                paused(s).copy(state = "RUNNING", anchor = clock.elapsed(), boot = clock.boot())
            }
            else -> return@withTransaction false
        }
        dao.update(next)
        true
    }

    suspend fun saveResult(id: String, value: Double, adjusted: Boolean = false, note: String = ""): Boolean {
        if (db.studyDao().active()?.taskSessionId == id) return false
        val s = dao.session(id) ?: return false
        if (s.questId != null) return quests.recordSession(s.questId, id, value, adjusted, note)
        val emitted = mutableListOf<GameEvent>()
        val saved = db.withTransaction {
            val current = dao.session(id) ?: return@withTransaction false
            if (current.state != "RESULT" || deadline(current.questId, current.itemId) == null) return@withTransaction false
            val item = dao.item(current.itemId!!) ?: return@withTransaction false
            val mode = MeasurementMode.valueOf(item.mode)
            if (!PlannerRules.validResult(value, mode)) return@withTransaction false
            val quantity = if (mode == MeasurementMode.CHECKBOX) item.target else value
            val total = item.progress + quantity
            if (!total.isFinite()) return@withTransaction false
            dao.update(item.copy(progress = total, done = total >= item.target))
            dao.update(current.copy(state = "SAVED", quantity = quantity, endedAt = day.now().toEpochMilli(), origin = if (adjusted) "MANUAL" else current.origin, note = note.trim()))
            coordinator.evaluate(emitted)
            true
        }
        emitted.forEach(events::emit)
        return saved
    }

    suspend fun removeResult(id: String): Boolean {
        val s = dao.session(id) ?: return false
        if (s.questId != null) return quests.removeSession(s.questId, id)
        return db.withTransaction {
            val current = dao.session(id) ?: return@withTransaction false
            val item = current.itemId?.let { dao.item(it) } ?: return@withTransaction false
            val plan = dao.planById(item.planId) ?: return@withTransaction false
            if (current.state != "SAVED" || plan.completedAt != null || plan.targetDay != day.today().toEpochDay()) return@withTransaction false
            dao.update(current.copy(state = "VOID"))
            val total = (item.progress - current.quantity).coerceAtLeast(0.0)
            dao.update(item.copy(progress = total, done = total >= item.target))
            true
        }
    }
}
