package com.tirthankar.ascent.data.backup

import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.repo.LoreRepository
import com.tirthankar.ascent.data.settings.BackupPrefs
import com.tirthankar.ascent.data.settings.RestoreRecord
import com.tirthankar.ascent.engine.BackupManifestCodec
import com.tirthankar.ascent.engine.LoreKinds
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first run after a restore's swap (PHASE5.md section 3, steps 5–6): checks the row
 * counts against the manifest, makes every unresolved day up to today neutral (D7),
 * records the summary and Undo restore, and unlocks *Conserved* once. Run by the daily
 * cycle before it resolves any day.
 */
@Singleton
class RestoreFinisher @Inject constructor(
    private val db: AscentDatabase,
    private val prefs: BackupPrefs,
    private val paths: BackupPaths,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val lore: LoreRepository,
    private val events: GameEvents,
) {
    /** True when a restore was just finished. */
    suspend fun finishIfPending(): Boolean {
        val file = paths.restoredMarker
        if (!file.exists()) return false
        val marker = BackupManifestCodec.decodeMarker(file.readText())
        if (marker == null) {
            file.delete()
            return false
        }
        val manifest = marker.manifest
        // Migrations only add rows, so every table must have at least what the backup had.
        val sql = db.openHelper.readableDatabase
        val countsOk = manifest.rowCounts.all { (table, expected) ->
            val actual = runCatching { sql.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getLong(0) } }.getOrNull()
            actual != null && actual >= expected
        }
        val today = gameDay.today().toEpochDay()
        val now = time.now().toEpochMilli()
        val createdAt = try {
            Instant.parse(manifest.createdAtUtc).toEpochMilli()
        } catch (_: DateTimeParseException) {
            now
        }
        prefs.recordRestore(
            RestoreRecord(
                fromFile = marker.fromFile,
                backupCreatedAt = createdAt,
                restoredAt = now,
                // Undo restore lasts until the next reset; an undo itself can't be undone.
                undoDay = if (marker.fromFile) today else null,
                countsOk = countsOk,
            ),
            neutralBefore = today,
        )
        if (marker.fromFile) {
            val emitted = mutableListOf<GameEvent>()
            db.withTransaction { lore.unlockOnce(LoreKinds.RESTORE, null, emitted) }
            emitted.forEach(events::emit)
        }
        file.delete()
        return true
    }

    /** D7: game days before this one that weren't resolved when a restore happened are neutral. */
    suspend fun neutralBefore(): Long? = prefs.current().neutralBefore
}
