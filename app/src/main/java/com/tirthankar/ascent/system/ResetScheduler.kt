package com.tirthankar.ascent.system

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.repo.SideQuestService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the next reset and the reminder 3 h before it. WorkManager timing isn't
 * exact, so the app also catches up on every open; the reset itself is idempotent.
 */
@Singleton
class ResetScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gameDay: GameDay,
    private val sideQuests: SideQuestService,
) {
    suspend fun schedule() {
        val now = gameDay.now()
        val nextReset = gameDay.nextReset()
        val work = WorkManager.getInstance(context)

        // A minute after the reset, so the new game day has surely begun.
        val untilReset = Duration.between(now, nextReset).plus(RESET_SLACK)
        work.enqueueUniqueWork(
            RESET_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyResetWorker>()
                .setInitialDelay(untilReset.toMillis(), TimeUnit.MILLISECONDS)
                .build(),
        )

        val reminderAt = nextReset.minus(REMINDER_BEFORE_END)
        val untilReminder = Duration.between(now, reminderAt)
        if (!untilReminder.isNegative) {
            work.enqueueUniqueWork(
                REMINDER_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(untilReminder.toMillis(), TimeUnit.MILLISECONDS)
                    .build(),
            )
        } else {
            work.cancelUniqueWork(REMINDER_WORK)
        }

        // The next emergency quest slot (Phase 3). Its window starts when it is shown.
        val emergency = sideQuests.nextEmergency(now)
        if (emergency != null) {
            work.enqueueUniqueWork(
                EMERGENCY_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EmergencyWorker>()
                    .setInitialDelay(Duration.between(now, emergency).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }

    private companion object {
        const val RESET_WORK = "daily-reset"
        const val REMINDER_WORK = "daily-reminder"
        const val EMERGENCY_WORK = "emergency-quest"
        val RESET_SLACK: Duration = Duration.ofMinutes(1)
        val REMINDER_BEFORE_END: Duration = Duration.ofHours(3)
    }
}
