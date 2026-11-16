package com.tirthankar.ascent.data.repo

import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.DailySnapshotEntity
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.data.model.toModel
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Saved day results, for the ASCENT STATUS and later the weekly Assessment. */
@Singleton
class HistoryRepository @Inject constructor(
    private val db: AscentDatabase,
) {
    /** Snapshots for the [days] days before [today], oldest first. */
    suspend fun lastDays(today: LocalDate, days: Int = 7): List<DailySnapshotEntity> =
        db.snapshotDao().getRange(today.minusDays(days.toLong()).toEpochDay(), today.minusDays(1).toEpochDay())

    /** Quests completed on [day]. */
    suspend fun completedOn(day: LocalDate): List<Quest> =
        db.questDao().getDay(day.toEpochDay())
            .filter { it.status == QuestStatus.DONE }
            .map { it.toModel(canUndo = false) }
}
