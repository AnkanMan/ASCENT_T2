package com.tirthankar.ascent.system

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tirthankar.ascent.NoticeTexts
import com.tirthankar.ascent.R
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.backup.BackupService
import com.tirthankar.ascent.data.repo.DailyCycle
import com.tirthankar.ascent.data.repo.SideQuestService
import com.tirthankar.ascent.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.ZoneId

/** Runs the daily reset (Task 28), then announces the new Daily Quest and overnight notices. */
@HiltWorker
class DailyResetWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cycle: DailyCycle,
    private val gameDay: GameDay,
    private val settings: SettingsRepository,
    private val notifications: SystemNotifications,
    private val scheduler: ResetScheduler,
    private val backups: BackupService,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = cycle.ensureUpToDate()
        // The daily backup (PHASE5.md section 3): after the reset, only if anything changed
        // and a folder is picked. A failure is shown in Settings; the reset still succeeds.
        backups.scheduledBackup()
        val quiet = settings.current().isQuiet(gameDay.now().atZone(ZoneId.systemDefault()).toLocalTime())
        if (result.issuedToday && result.todayObjectives > 0) {
            notifications.questIssued(result.todayObjectives, gameDay.nextReset(), silent = quiet)
        }
        // Gates that closed, a penalty issued, a boss that stirs (Phase 3).
        result.notices.filter { NoticeTexts.notifies(it.kind) }.forEach { notice ->
            val body = if (NoticeTexts.isGate(notice.kind)) {
                notice.subject + " · " + applicationContext.getString(R.string.notice_gate_xp, notice.detail.orEmpty())
            } else {
                listOfNotNull(notice.subject, notice.detail).joinToString(" — ")
            }
            notifications.notice(applicationContext.getString(NoticeTexts.header(notice.kind)), body, notice.kind.ordinal, silent = quiet)
        }
        scheduler.schedule()
        return Result.success()
    }
}

/** Three hours before the day ends, if the Daily isn't done (Task 39). Once per day. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cycle: DailyCycle,
    private val gameDay: GameDay,
    private val settings: SettingsRepository,
    private val notifications: SystemNotifications,
    private val scheduler: ResetScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        cycle.ensureUpToDate()
        val today = gameDay.today().toEpochDay()
        val progress = cycle.todayProgress()
        if (progress.objectives > 0 && !progress.complete && settings.lastReminderDay() != today) {
            val quiet = settings.current().isQuiet(gameDay.now().atZone(ZoneId.systemDefault()).toLocalTime())
            notifications.reminder(progress.done, progress.objectives, gameDay.nextReset(), silent = quiet)
            settings.setLastReminderDay(today)
        }
        scheduler.schedule()
        return Result.success()
    }
}

/** Issues an emergency quest at its slot (Task 17): about twice a week, 2 hours to finish. */
@HiltWorker
class EmergencyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cycle: DailyCycle,
    private val sideQuests: SideQuestService,
    private val gameDay: GameDay,
    private val settings: SettingsRepository,
    private val notifications: SystemNotifications,
    private val scheduler: ResetScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        cycle.ensureUpToDate()
        sideQuests.issueEmergencyIfDue()?.let { issued ->
            val quiet = settings.current().isQuiet(gameDay.now().atZone(ZoneId.systemDefault()).toLocalTime())
            notifications.emergency(issued.title, issued.endsAt, silent = quiet)
        }
        scheduler.schedule()
        return Result.success()
    }
}
