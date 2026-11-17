package com.tirthankar.ascent.data.repo

import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.HabitObservationEntity
import com.tirthankar.ascent.data.db.HabitSeriesCacheEntity
import com.tirthankar.ascent.data.db.QuestEntity
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.engine.HabitSeries
import com.tirthankar.ascent.engine.HabitStrength
import com.tirthankar.ascent.engine.SeriesPeriod
import com.tirthankar.ascent.engine.StrengthObservation
import com.tirthankar.ascent.engine.StrengthSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A series' strength with its sparkline, for the quest detail. */
data class HabitDetail(
    val seriesId: String,
    val period: SeriesPeriod,
    val summary: StrengthSummary,
    /** Strength after each of the last 90 observed periods, oldest first. */
    val trajectory: List<Double>,
)

/**
 * Habit strength per series (PHASE5.md section 1).
 *
 * - A completion records x = 1 for its period at once, so the score moves when you
 *   tick the quest; an undo removes it again.
 * - The reset records each issued period's final actual/target fraction.
 * - Powers that change history afterwards (Unbroken) never touch it (D3).
 *
 * The cache holds the latest value per series; every write refreshes it, and a miss
 * recomputes from the full history.
 */
@Singleton
class HabitRepository @Inject constructor(
    private val db: AscentDatabase,
) {
    private val dao = db.habitDao()

    /** Cached strengths by series id, for the quest cards. */
    val strengths: Flow<Map<String, StrengthSummary>> =
        dao.observeCache().map { rows -> rows.associate { it.seriesId to it.toSummary() } }

    /** The series' strength, streaks and count; from the cache when it's there. */
    suspend fun summary(seriesId: String): StrengthSummary {
        dao.cache(seriesId)?.let { return it.toSummary() }
        return refresh(seriesId)
    }

    suspend fun detail(seriesId: String): HabitDetail {
        val period = period(seriesId)
        val history = history(seriesId)
        return HabitDetail(seriesId, period, summary(seriesId), HabitStrength.trajectory(history, period))
    }

    /**
     * Records [quest]'s actual/target fraction (or full completion).
     * Call only for periods that count: not rest days or neutral days.
     */
    internal suspend fun record(quest: QuestEntity, done: Boolean, at: Long) {
        val seriesId = quest.seriesId ?: return
        dao.upsertObservation(
            HabitObservationEntity(
                seriesId = seriesId,
                periodKey = quest.day,
                x = HabitStrength.x(if (done) maxOf(quest.target, quest.actualProgress) else quest.actualProgress, quest.target),
                target = quest.target,
                progress = if (done) maxOf(quest.target, quest.actualProgress) else quest.actualProgress,
                recordedAt = at,
            ),
        )
        refresh(seriesId)
    }

    /** Removes [quest]'s current-period observation (an undo before the reset). */
    internal suspend fun clear(quest: QuestEntity) {
        val seriesId = quest.seriesId ?: return
        dao.deleteObservation(seriesId, quest.day)
        refresh(seriesId)
    }

    /** Recomputes [seriesId] from scratch and stores the result (or drops it when empty). */
    suspend fun refresh(seriesId: String): StrengthSummary {
        val period = period(seriesId)
        val history = history(seriesId)
        val summary = HabitStrength.summarize(history, period)
        val last = history.maxOfOrNull { it.periodKey }
        if (last == null) {
            dao.invalidate(seriesId)
        } else {
            dao.upsertCache(
                HabitSeriesCacheEntity(
                    seriesId = seriesId,
                    period = period,
                    strength = summary.strength,
                    lastPeriodKey = last,
                    streak = summary.streak,
                    best = summary.bestStreak,
                    observations = summary.observations,
                    sinceKey = summary.sinceKey,
                ),
            )
        }
        return summary
    }

    /** Fills the cache for series that have observations but no cached value (after the v4 backfill). */
    suspend fun warm() {
        val cached = dao.allCache().map { it.seriesId }.toSet()
        dao.seriesIds().filter { it !in cached }.forEach { refresh(it) }
    }

    suspend fun invalidate(seriesId: String) = dao.invalidate(seriesId)

    /** Template series are daily; a recurring chain follows its quests' scope. */
    suspend fun period(seriesId: String): SeriesPeriod {
        if (HabitSeries.isTemplate(seriesId)) return SeriesPeriod.DAY
        return if (db.questDao().scopeOfSeries(seriesId) == QuestScope.WEEKLY) SeriesPeriod.WEEK else SeriesPeriod.DAY
    }

    private suspend fun history(seriesId: String): List<StrengthObservation> =
        dao.observations(seriesId).map { StrengthObservation(it.periodKey, it.x) }

    private fun HabitSeriesCacheEntity.toSummary(): StrengthSummary {
        val pct = HabitStrength.percent(strength)
        return StrengthSummary(strength, pct, HabitStrength.label(pct), streak, best, observations, sinceKey)
    }
}
