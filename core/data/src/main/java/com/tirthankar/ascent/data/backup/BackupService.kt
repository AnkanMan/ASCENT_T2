package com.tirthankar.ascent.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.settings.BackupPrefs
import com.tirthankar.ascent.engine.BackupManifest
import com.tirthankar.ascent.engine.BackupNames
import com.tirthankar.ascent.engine.BackupPlayer
import com.tirthankar.ascent.engine.BackupRetention
import com.tirthankar.ascent.engine.PowerHandlers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** What a backup run did. */
sealed interface BackupResult {
    data class Written(val name: String) : BackupResult
    /** Scheduled run: nothing changed since the last backup. */
    data object Unchanged : BackupResult
    data object NoFolder : BackupResult
    data class Failed(val reason: String) : BackupResult
}

/**
 * Backup and restore (PHASE5.md section 3, D6–D8).
 *
 * A backup copies the Room database while writes are paused, folds its WAL in, drops
 * Clarity's journal if asked, and zips it with the settings and a manifest. A restore
 * checks a file, saves the current state as the pre-restore backup, and stages the
 * files; the app then restarts and [BackupArchive.applyPending] swaps them in before
 * Room or DataStore open. [RestoreFinisher] does the rest on the first run after.
 */
@Singleton
class BackupService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AscentDatabase,
    private val prefs: BackupPrefs,
    private val paths: BackupPaths,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val content: ContentRepository,
) {

    // ---- Folder ----

    /** Keeps the picked folder's permission across restarts, and drops the previous one's. */
    suspend fun setFolder(uri: String) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(Uri.parse(uri), flags)
        prefs.current().folderUri?.takeIf { it != uri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        prefs.setFolder(uri)
    }

    fun folderLabel(uri: String): String? = runCatching { SafBackupFolder(context, Uri.parse(uri)).label() }.getOrNull()

    // ---- Backup ----

    /** **Back up now**: always writes a file. */
    suspend fun backupNow(): BackupResult = toPickedFolder(force = true)

    /** The daily run after the reset: only if anything changed since the last backup. */
    suspend fun scheduledBackup(): BackupResult = toPickedFolder(force = false)

    private suspend fun toPickedFolder(force: Boolean): BackupResult {
        val uri = prefs.current().folderUri ?: return BackupResult.NoFolder
        return writeBackup(SafBackupFolder(context, Uri.parse(uri)), force)
    }

    /** Writes a backup into [folder] and applies retention there. */
    suspend fun writeBackup(folder: BackupFolder, force: Boolean): BackupResult = withContext(Dispatchers.IO) {
        try {
            val state = prefs.current()
            val snap = snapshot(state.excludeJournal)
            val combined = BackupArchive.sha256("${snap.dbHash}|${snap.datastoreHash}|${state.excludeJournal}")
            if (!force && combined == state.lastBackupHash) return@withContext BackupResult.Unchanged
            val now = time.now()
            val name = BackupNames.name(LocalDateTime.ofInstant(now, time.zone()))
            val manifest = snap.manifest(now, state.excludeJournal)
            folder.write(name) { out -> BackupArchive.write(out, manifest, snap.db, snap.datastore) }
            BackupRetention.toDelete(folder.list()).forEach { folder.delete(it) }
            prefs.recordBackup(now.toEpochMilli(), name, combined)
            BackupResult.Written(name)
        } catch (e: IOException) {
            failed(e)
        } catch (e: SecurityException) {
            failed(e)
        } catch (e: SQLiteException) {
            failed(e)
        } catch (e: IllegalArgumentException) {
            failed(e)
        } finally {
            paths.temp.deleteRecursively()
        }
    }

    private suspend fun failed(e: Exception): BackupResult {
        val reason = e.message ?: e.javaClass.simpleName
        prefs.recordError(reason)
        return BackupResult.Failed(reason)
    }

    /** The whole current state, journal included, as [file] (the pre-restore backup). */
    private suspend fun writeFile(file: File) {
        val snap = snapshot(excludeJournal = false)
        val manifest = snap.manifest(time.now(), false)
        file.parentFile?.mkdirs()
        file.outputStream().use { out -> BackupArchive.write(out, manifest, snap.db, snap.datastore) }
        paths.temp.deleteRecursively()
    }

    private inner class Snapshot(
        val db: File,
        val dbHash: String,
        val datastore: File?,
        val datastoreHash: String?,
        val rowCounts: Map<String, Long>,
        val player: BackupPlayer?,
    ) {
        suspend fun manifest(now: Instant, journalExcluded: Boolean) = BackupManifest(
            appVersion = appVersion(),
            schemaVersion = AscentDatabase.VERSION,
            createdAtUtc = now.toString(),
            createdAtLocal = LocalDateTime.ofInstant(now, time.zone()).toString(),
            gameDay = gameDay.today().toString(),
            rowCounts = rowCounts,
            dbSha256 = dbHash,
            datastoreSha256 = datastoreHash,
            journalExcluded = journalExcluded,
            player = player,
        )
    }

    /**
     * A consistent copy of the database (PHASE5.md section 3): the WAL is checkpointed,
     * then the files are copied while a transaction holds the write lock, so no write
     * lands halfway. The copy's WAL is folded in, and D8 applied to the copy only.
     */
    private suspend fun snapshot(excludeJournal: Boolean): Snapshot {
        val dir = paths.temp.apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, "snapshot.db")
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        db.withTransaction {
            paths.database.copyTo(copy, overwrite = true)
            File(paths.database.path + "-wal").takeIf { it.exists() }?.copyTo(File(copy.path + "-wal"), overwrite = true)
        }
        var counts: Map<String, Long> = emptyMap()
        var player: BackupPlayer? = null
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            if (excludeJournal) {
                val ids = clarityIds()
                if (ids.isNotEmpty()) {
                    sql.execSQL("UPDATE power_cast SET note = NULL WHERE skillId IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray())
                }
            }
            counts = tables(sql).associateWith { t -> sql.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { c -> c.moveToFirst(); c.getLong(0) } }
            player = sql.rawQuery("SELECT level, totalXp, streakDays FROM player LIMIT 1", null).use { c ->
                if (c.moveToFirst()) BackupPlayer(c.getInt(0), c.getLong(1), c.getInt(2)) else null
            }
            // Folds the WAL into the file, so the backup is one self-contained database.
            sql.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        }
        listOf("-wal", "-shm", "-journal").forEach { File(copy.path + it).delete() }
        // Settings: a copy, so the hash in the manifest is the hash of what's zipped.
        val settings = paths.datastore.takeIf { it.exists() }?.copyTo(File(dir, "settings.preferences_pb"), overwrite = true)
        return Snapshot(copy, BackupArchive.sha256(copy), settings, settings?.let(BackupArchive::sha256), counts, player)
    }

    private fun tables(sql: SQLiteDatabase): List<String> =
        sql.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.filter { it !in INTERNAL_TABLES && !it.startsWith("sqlite_") }.sorted()

    /** D8: Clarity's skills keep their journal in the cast note. */
    private fun clarityIds(): List<String> =
        content.content.skills.skills.filter { it.handler == PowerHandlers.CLARITY }.map { it.id }

    @Suppress("DEPRECATION")
    private fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    // ---- Restore ----

    /** Copies a picked file in and checks it. Nothing live is touched. */
    suspend fun inspect(uri: String): RestoreCheck = withContext(Dispatchers.IO) {
        try {
            paths.workDir.mkdirs()
            val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return@withContext RestoreCheck.NotABackup
            input.use { i -> paths.incoming.outputStream().use { i.copyTo(it) } }
            BackupArchive.inspect(paths.incoming, AscentDatabase.VERSION, paths.temp)
        } catch (_: IOException) {
            RestoreCheck.Corrupt
        } catch (_: SecurityException) {
            RestoreCheck.NotABackup
        } finally {
            paths.temp.deleteRecursively()
        }
    }

    /** As [inspect], for a file (tests, and the pre-restore backup). */
    suspend fun inspectFile(file: File): RestoreCheck = withContext(Dispatchers.IO) {
        paths.workDir.mkdirs()
        if (file != paths.incoming) file.copyTo(paths.incoming, overwrite = true)
        BackupArchive.inspect(paths.incoming, AscentDatabase.VERSION, paths.temp).also { paths.temp.deleteRecursively() }
    }

    /**
     * After the player confirms: checks the file again, saves the current state as the
     * pre-restore backup, and stages the restore. The caller then restarts the app.
     */
    suspend fun confirmRestore(): RestoreCheck = withContext(Dispatchers.IO) {
        val check = BackupArchive.inspect(paths.incoming, AscentDatabase.VERSION, paths.temp)
        paths.temp.deleteRecursively()
        if (check !is RestoreCheck.Ok) return@withContext check
        try {
            writeFile(paths.preRestore)
            BackupArchive.stage(paths.incoming, paths.staging, fromFile = true, manifest = check.manifest)
            paths.incoming.delete()
            check
        } catch (e: IOException) {
            unstage(e)
        } catch (e: SQLiteException) {
            unstage(e)
        }
    }

    /** A failed preparation leaves nothing staged, so nothing is swapped at the next start. */
    private fun unstage(e: Exception): RestoreCheck {
        paths.staging.deleteRecursively()
        paths.temp.deleteRecursively()
        return RestoreCheck.Failed(e.message ?: e.javaClass.simpleName)
    }

    fun cancelRestore() {
        paths.incoming.delete()
    }

    /** Undo restore is offered until the next reset, while the pre-restore backup exists. */
    suspend fun canUndoRestore(): Boolean =
        paths.preRestore.exists() && prefs.current().lastRestore?.undoDay == gameDay.today().toEpochDay()

    /** Stages the pre-restore backup. The caller then restarts the app. */
    suspend fun prepareUndo(): RestoreCheck = withContext(Dispatchers.IO) {
        if (!canUndoRestore()) return@withContext RestoreCheck.NotABackup
        val check = BackupArchive.inspect(paths.preRestore, AscentDatabase.VERSION, paths.temp)
        paths.temp.deleteRecursively()
        if (check !is RestoreCheck.Ok) return@withContext check
        try {
            BackupArchive.stage(paths.preRestore, paths.staging, fromFile = false, manifest = check.manifest)
            check
        } catch (e: IOException) {
            unstage(e)
        }
    }

    /** Restarts the app so the staged files are swapped in before anything opens them. */
    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        val INTERNAL_TABLES = setOf("android_metadata", "room_master_table")
    }
}
