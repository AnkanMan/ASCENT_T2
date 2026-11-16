package com.tirthankar.ascent.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The DataStore that holds [BackupState]: device-local, never inside a backup. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupStore

/** The last restore, for the summary and Undo restore (PHASE5.md section 3). */
data class RestoreRecord(
    /** True for a restore from a file; false for an Undo restore. */
    val fromFile: Boolean,
    /** When the restored backup was made (epoch millis). */
    val backupCreatedAt: Long,
    val restoredAt: Long,
    /** Undo restore is offered while the game day is still this one. */
    val undoDay: Long?,
    /** Every table has at least the manifest's rows after migrating. */
    val countsOk: Boolean,
)

/** Backup bookkeeping (PHASE5.md section 4). */
data class BackupState(
    /** The Storage Access Framework tree the player picked, with a persisted permission. */
    val folderUri: String? = null,
    val lastBackupAt: Long? = null,
    val lastBackupName: String? = null,
    /** SHA-256 over the last backed-up database and settings, to skip a backup when nothing changed. */
    val lastBackupHash: String? = null,
    /** D8: leave Clarity's journal out of backups. */
    val excludeJournal: Boolean = false,
    /** The last scheduled backup's failure, cleared by the next success. */
    val lastError: String? = null,
    /** D7: game days before this one, not yet resolved when a restore happened, are neutral. */
    val neutralBefore: Long? = null,
    val lastRestore: RestoreRecord? = null,
)

/**
 * Backup settings and state, in their own file (`ascent_backup`). Kept apart from the
 * game settings so a restore never brings back another phone's folder permission or
 * backup times, and survives the swap of the game's files.
 */
@Singleton
class BackupPrefs @Inject constructor(
    @param:BackupStore private val dataStore: DataStore<Preferences>,
) {
    val state: Flow<BackupState> = dataStore.data.map { p ->
        BackupState(
            folderUri = p[FOLDER],
            lastBackupAt = p[LAST_AT],
            lastBackupName = p[LAST_NAME],
            lastBackupHash = p[LAST_HASH],
            excludeJournal = p[EXCLUDE_JOURNAL] ?: false,
            lastError = p[LAST_ERROR],
            neutralBefore = p[NEUTRAL_BEFORE],
            lastRestore = p[RESTORED_AT]?.let { at ->
                RestoreRecord(
                    fromFile = (p[RESTORE_KIND] ?: 1) == 1,
                    backupCreatedAt = p[RESTORE_BACKUP_AT] ?: at,
                    restoredAt = at,
                    undoDay = p[RESTORE_UNDO_DAY],
                    countsOk = p[RESTORE_COUNTS_OK] ?: true,
                )
            },
        )
    }

    suspend fun current(): BackupState = state.first()

    suspend fun setFolder(uri: String?) {
        dataStore.edit { if (uri == null) it.remove(FOLDER) else it[FOLDER] = uri }
    }

    suspend fun setExcludeJournal(exclude: Boolean) {
        dataStore.edit { it[EXCLUDE_JOURNAL] = exclude }
    }

    suspend fun recordBackup(at: Long, name: String, hash: String) {
        dataStore.edit {
            it[LAST_AT] = at
            it[LAST_NAME] = name
            it[LAST_HASH] = hash
            it.remove(LAST_ERROR)
        }
    }

    suspend fun recordError(message: String) {
        dataStore.edit { it[LAST_ERROR] = message }
    }

    /** After a swap: gap days are neutral, and the summary and Undo restore show. */
    suspend fun recordRestore(record: RestoreRecord, neutralBefore: Long) {
        dataStore.edit {
            it[NEUTRAL_BEFORE] = neutralBefore
            it[RESTORED_AT] = record.restoredAt
            it[RESTORE_BACKUP_AT] = record.backupCreatedAt
            it[RESTORE_KIND] = if (record.fromFile) 1 else 0
            it[RESTORE_COUNTS_OK] = record.countsOk
            if (record.undoDay == null) it.remove(RESTORE_UNDO_DAY) else it[RESTORE_UNDO_DAY] = record.undoDay
            // The restored database is a different state: the next scheduled backup isn't skipped.
            it.remove(LAST_HASH)
        }
    }

    private companion object {
        val FOLDER = stringPreferencesKey("backupFolderUri")
        val LAST_AT = longPreferencesKey("lastBackupAt")
        val LAST_NAME = stringPreferencesKey("lastBackupName")
        val LAST_HASH = stringPreferencesKey("lastBackupHash")
        val EXCLUDE_JOURNAL = booleanPreferencesKey("excludeJournalFromBackup")
        val LAST_ERROR = stringPreferencesKey("lastBackupError")
        val NEUTRAL_BEFORE = longPreferencesKey("neutralBefore")
        val RESTORED_AT = longPreferencesKey("restoredAt")
        val RESTORE_BACKUP_AT = longPreferencesKey("restoredBackupAt")
        val RESTORE_KIND = intPreferencesKey("restoreKind")
        val RESTORE_UNDO_DAY = longPreferencesKey("restoreUndoDay")
        val RESTORE_COUNTS_OK = booleanPreferencesKey("restoreCountsOk")
    }
}
