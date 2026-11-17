package com.tirthankar.ascent.data.repo

import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.db.*
import com.tirthankar.ascent.engine.PlannerRules
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** DAO-only coordinator. Call inside the owner's write transaction. */
@Singleton
class PlannerCoordinator @Inject constructor(private val db: AscentDatabase, private val day: GameDay) {
    suspend fun evaluate(emitted: MutableList<GameEvent>) {
        val today = day.today().toEpochDay()
        val dao = db.plannerDao()
        val plan = dao.plan(today) ?: return
        if (plan.completedAt != null || plan.state !in listOf("ACTIVE", "SCHEDULED")) return
        val done = dao.items(plan.id).map { item ->
            item.questId?.let { db.questDao().getById(it)?.status == QuestStatus.DONE } ?: item.done
        }
        if (!PlannerRules.allDone(done)) return
        val now = day.now().toEpochMilli()
        dao.update(plan.copy(state = "COMPLETED", completedAt = now))
        db.grantXp(PlannerRules.COMPLETION_XP, emitted, XpLog(XpSource.PLAN_COMPLETED, today, now, plan.id))
        emitted += GameEvent.Notice(NoticeKind.PLAN_COMPLETED, LocalDate.ofEpochDay(plan.targetDay).toString())
    }

    suspend fun reconcile(neutralBefore: Long = Long.MIN_VALUE) {
        val today = day.today().toEpochDay()
        val dao = db.plannerDao()
        dao.plans().filter { it.state in listOf("SCHEDULED", "ACTIVE") || (it.state == "EXPIRED" && it.targetDay < neutralBefore) }.forEach { plan ->
            when {
                plan.targetDay < today -> dao.update(plan.copy(state = if (plan.targetDay < neutralBefore) "NEUTRAL" else "EXPIRED"))
                plan.targetDay == today && plan.state == "SCHEDULED" -> dao.update(plan.copy(state = "ACTIVE"))
            }
        }
        val now = day.now().toEpochMilli()
        val clock = day.clock()
        db.studyDao().active()?.let { study ->
            val end = clock.dayStart(LocalDate.ofEpochDay(study.day + 1)).toEpochMilli()
            if (study.day != today || end <= now) db.interruptStudy(study, "INCOMPLETE", now)
        }
        dao.sessions().filter { it.state in listOf("RUNNING", "PAUSED", "RESULT") }.forEach { session ->
            val q = session.questId?.let { db.questDao().getById(it) }
            val item = session.itemId?.let { dao.item(it) }
            val plan = item?.let { dao.planById(it.planId) }
            val end = when {
                q != null -> clock.dayStart(LocalDate.ofEpochDay(q.day).plusDays(if (q.scope == QuestScope.DAILY) 1 else 7)).toEpochMilli()
                plan != null -> clock.dayStart(LocalDate.ofEpochDay(plan.targetDay + 1)).toEpochMilli()
                else -> session.endsAt
            }
            when {
                end <= now -> dao.update(session.copy(state = "EXPIRED", anchor = null, endedAt = end, endsAt = end))
                end != session.endsAt -> dao.update(session.copy(endsAt = end))
            }
        }
    }
}
