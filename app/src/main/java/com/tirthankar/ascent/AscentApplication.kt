package com.tirthankar.ascent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tirthankar.ascent.data.backup.BackupArchive
import com.tirthankar.ascent.data.backup.BackupPaths
import com.tirthankar.ascent.data.repo.DailyCycle
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.system.ResetScheduler
import com.tirthankar.ascent.system.SystemNotifications
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AscentApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var cycle: DailyCycle
    @Inject lateinit var scheduler: ResetScheduler
    @Inject lateinit var notifications: SystemNotifications
    @Inject lateinit var settings: SettingsRepository

    /** Lives as long as the process; for the start-up catch-up and rescheduling. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // A staged restore is swapped in first, before Hilt builds anything that could open
        // the database or the settings (PHASE5.md section 3, step 4).
        BackupArchive.applyPending(BackupPaths.forContext(this))
        super.onCreate()
        notifications.createChannel()
        appScope.launch {
            // Catch up any reset WorkManager missed, then schedule the next ones.
            cycle.ensureUpToDate()
            scheduler.schedule()
        }
        // A new reset time moves the schedule.
        settings.settings.map { it.resetTime }
            .distinctUntilChanged()
            .onEach { scheduler.schedule() }
            .launchIn(appScope)
    }
}
