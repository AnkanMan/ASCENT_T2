package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.db.*
import com.tirthankar.ascent.engine.*
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.random.Random

/** Called in the caller's transaction; keeps terminal attempts and closes their quest marker. */
internal suspend fun AscentDatabase.interruptStudy(s: StudySessionEntity, state: String, at: Long) {
    studyDao().update(s.copy(state = state, anchor = null, endedAt = at))
    s.taskSessionId?.let { plannerDao().session(it) }?.let {
        if (it.state in listOf("RUNNING", "PAUSED", "RESULT")) {
            plannerDao().update(it.copy(state = "VOID", anchor = null, endedAt = at, activeSeconds = s.activeSeconds))
        }
    }
}

/** Safe reconciliation shared by task entry points; never grants rewards. */
internal suspend fun reconcileStudy(db: AscentDatabase, day: GameDay, clock: SessionClock) {
    val s = db.studyDao().active() ?: return
    val now = day.now().toEpochMilli()
    val end = day.clock().dayStart(LocalDate.ofEpochDay(s.day + 1)).toEpochMilli()
    when {
        s.day != day.today().toEpochDay() || now >= end -> db.interruptStudy(s, "INCOMPLETE", now)
        s.boot != clock.boot() || (s.state == "RUNNING" && (s.anchor == null || s.anchor > clock.elapsed())) -> db.interruptStudy(s, "INTERRUPTED", now)
        else -> {
            val seconds = PlannerRules.elapsed(s.activeSeconds, s.anchor.takeIf { s.state == "RUNNING" }, s.boot, clock.elapsed(), clock.boot())
            if (end != s.endsAt || (s.state == "RUNNING" && seconds - s.activeSeconds >= 5)) {
                db.studyDao().update(s.copy(endsAt = end, activeSeconds = seconds,
                    anchor = if (s.state == "RUNNING") (s.anchor ?: clock.elapsed()) + (seconds - s.activeSeconds) * 1000 else null))
            }
        }
    }
}

data class StudySnapshot(
    val today: Long,
    val attempts: Int,
    val sessions: List<StudySessionEntity>,
    val active: StudySessionEntity?,
    val quests: List<QuestEntity>,
    val elapsed: Long,
    val timerBlocked: Boolean,
)

@Singleton
class StudyRepository @Inject constructor(
    private val db: AscentDatabase,
    private val day: GameDay,
    private val clock: SessionClock,
    private val quests: QuestRepository,
    private val skills: SkillRepository,
    private val events: GameEvents,
) {
    private fun current(q: QuestEntity, today: LocalDate): Boolean = q.day ==
        (if (q.scope == QuestScope.DAILY) today else GameClock.weekStart(today)).toEpochDay()

    private suspend fun candidates(): List<QuestEntity> {
        val today = day.today()
        return db.questDao().all().filter { q ->
            q.kind == QuestKind.NORMAL && q.status == QuestStatus.OPEN && q.realm == Realm.MIND &&
                q.mode() == MeasurementMode.DURATION && q.unit in listOf(TargetUnit.SECONDS, TargetUnit.MINUTES, TargetUnit.HOURS) && current(q, today)
        }
    }

    private suspend fun otherTimer(): Boolean = db.plannerDao().sessions().any { it.state == "RUNNING" } ||
        db.skillDao().activeCasts().any { it.note == null && skills.definition(it.skillId)?.handler == PowerHandlers.HYPERFOCUS }

    private fun elapsed(s: StudySessionEntity): Long = PlannerRules.elapsed(s.activeSeconds,
        s.anchor.takeIf { s.state == "RUNNING" }, s.boot, clock.elapsed(), clock.boot())

    suspend fun snapshot(): StudySnapshot = db.withTransaction {
        reconcileStudy(db, day, clock)
        val active = db.studyDao().active()
        val today = day.today().toEpochDay()
        StudySnapshot(today, db.studyDao().attempts(today), db.studyDao().all(), active, candidates(),
            active?.let(::elapsed) ?: 0, if (active == null) otherTimer() else false)
    }

    suspend fun start(subject: String, objective: String, plannedSeconds: Long, questId: Long? = null): Long? = db.withTransaction {
        reconcileStudy(db, day, clock)
        val today = day.today().toEpochDay()
        if (db.playerDao().getPlayer() == null || db.studyDao().active() != null || db.studyDao().attempts(today) >= StudyRules.MAX_ATTEMPTS || otherTimer()) return@withTransaction null
        if (subject.isBlank() || subject.length > 120 || objective.isBlank() || objective.length > 500 || SafetyRules.isForbidden(objective)) return@withTransaction null
        val q = questId?.let { id -> candidates().find { it.id == id } ?: return@withTransaction null }
        if (q != null && db.plannerDao().sessions().any { it.questId == q.id && it.state in listOf("RUNNING", "PAUSED", "RESULT") }) return@withTransaction null
        val linkedSeconds = q?.let { ceil(it.target * when (it.unit) { TargetUnit.HOURS -> 3600; TargetUnit.MINUTES -> 60; else -> 1 }).toLong() } ?: 0
        val now = day.now().toEpochMilli()
        val end = day.nextReset().toEpochMilli()
        if (!StudyRules.validDuration(plannedSeconds, linkedSeconds) || plannedSeconds > (end - now) / 1000) return@withTransaction null
        val previous = db.studyDao().all().firstOrNull()?.quoteIndex
        val quote = StudyRules.quote(previous, Random.nextInt(if (previous == null) StudyRules.QUOTE_COUNT else StudyRules.QUOTE_COUNT - 1))
        val taskId = q?.let { UUID.randomUUID().toString() }
        if (taskId != null) db.plannerDao().insert(TaskSessionEntity(taskId, questId = q.id, state = "RUNNING", startedAt = now, endsAt = end, anchor = clock.elapsed(), boot = clock.boot()))
        db.studyDao().insert(StudySessionEntity(day = today, subject = subject.trim(), objective = objective.trim(),
            plannedSeconds = plannedSeconds, quoteIndex = quote, questId = questId, taskSessionId = taskId,
            startedAt = now, endsAt = end, anchor = clock.elapsed(), boot = clock.boot()))
    }

    /** Stop time once. Early finishes are terminal attempts with no contribution or XP. */
    suspend fun finish(id: Long, early: Boolean = false): Boolean = db.withTransaction {
        reconcileStudy(db, day, clock)
        val s = db.studyDao().get(id) ?: return@withTransaction false
        if (s.state != "RUNNING") return@withTransaction false
        val stopped = s.copy(activeSeconds = elapsed(s), anchor = null, endedAt = day.now().toEpochMilli())
        if (early || stopped.activeSeconds < s.plannedSeconds) db.interruptStudy(stopped, "INCOMPLETE", stopped.endedAt!!)
        else {
            db.studyDao().update(stopped.copy(state = "RESULT"))
            s.taskSessionId?.let { db.plannerDao().session(it) }?.let {
                db.plannerDao().update(it.copy(state = "RESULT", activeSeconds = stopped.activeSeconds, anchor = null))
            }
        }
        true
    }

    suspend fun discard(id: Long): Boolean = db.withTransaction {
        reconcileStudy(db, day, clock)
        val s = db.studyDao().get(id) ?: return@withTransaction false
        if (s.state !in listOf("RUNNING", "RESULT")) return@withTransaction false
        db.interruptStudy(s.copy(activeSeconds = elapsed(s)), "DISCARDED", day.now().toEpochMilli())
        true
    }

    /** Claim once, including the ordinary quest completion and its existing planner evaluation. */
    suspend fun submit(id: Long, learned: String, nextStep: String): Boolean {
        val emitted = mutableListOf<GameEvent>()
        val saved = db.withTransaction {
            reconcileStudy(db, day, clock)
            val s = db.studyDao().get(id) ?: return@withTransaction false
            if (s.state != "RESULT" || learned.isBlank() || learned.length > 2000 || nextStep.length > 1000) return@withTransaction false
            val xp = StudyRules.reward(s.activeSeconds, s.plannedSeconds)
            if (xp == 0L) return@withTransaction false
            var questXp = 0L
            if (s.questId != null) {
                val q = db.questDao().getById(s.questId) ?: return@withTransaction false
                if (q.status != QuestStatus.OPEN || !quests.recordSession(q.id, s.taskSessionId!!, PlannerRules.quantity(s.activeSeconds, q.unit), false, learned.trim())) return@withTransaction false
                questXp = db.questDao().getById(q.id)?.xpAwarded ?: 0
            }
            if (db.grantXp(xp, emitted, XpLog(XpSource.STUDY, s.day, day.now().toEpochMilli(), s.id)) == null) return@withTransaction false
            db.studyDao().update(s.copy(state = "COMPLETED", learned = learned.trim(), nextStep = nextStep.trim(), xpAwarded = xp, questXpAwarded = questXp))
            true
        }
        emitted.forEach(events::emit)
        return saved
    }
}
