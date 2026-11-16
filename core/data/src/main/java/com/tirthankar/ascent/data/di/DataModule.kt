package com.tirthankar.ascent.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.tirthankar.ascent.data.backup.BackupPaths
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.settings.BackupStore
import com.tirthankar.ascent.engine.RewardCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.random.Random

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AscentDatabase =
        // No fallbackToDestructiveMigration, ever: player data is never lost.
        Room.databaseBuilder(context, AscentDatabase::class.java, AscentDatabase.NAME)
            .addMigrations(*AscentDatabase.MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("ascent_settings") }

    /** Backup bookkeeping (Phase 5): its own file, device-local, never backed up. */
    @Provides
    @Singleton
    @BackupStore
    fun backupStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("ascent_backup") }

    /** The live database and settings files, and the backup work folder (Phase 5). */
    @Provides
    @Singleton
    fun backupPaths(@ApplicationContext context: Context): BackupPaths = BackupPaths.forContext(context)

    @Provides
    @Singleton
    fun random(): Random = Random.Default

    @Provides
    @Singleton
    fun rewardCalculator(random: Random): RewardCalculator = RewardCalculator(random)
}

// The TimeSource binding lives in :app, per build type: release uses SystemTimeSource,
// debug a clock the debug panel can fast-forward (Phase 4 Task 12).
