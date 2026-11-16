package com.tirthankar.ascent.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.engine.BackupManifest
import com.tirthankar.ascent.engine.BackupManifestCodec
import com.tirthankar.ascent.engine.RestoreMarker
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Where the backup machinery keeps its files. [database] and [datastore] are the live
 * files a restore replaces; [workDir] holds staging, the pre-restore backup and markers.
 */
class BackupPaths(
    val database: File,
    val datastore: File,
    val workDir: File,
) {
    val staging: File get() = File(workDir, "staging")
    val preRestore: File get() = File(workDir, "pre-restore.zip")
    /** Left by the swap for the first run after it (PHASE5.md section 3, steps 5–6). */
    val restoredMarker: File get() = File(workDir, "restored.json")
    /** A picked file, copied here to be checked before anything else happens. */
    val incoming: File get() = File(workDir, "incoming.zip")
    val temp: File get() = File(workDir, "tmp")

    companion object {
        /** The app's real files: `ascent.db` and the `ascent_settings` DataStore. */
        fun forContext(context: Context): BackupPaths = BackupPaths(
            database = context.getDatabasePath(AscentDatabase.NAME),
            datastore = File(context.filesDir, "datastore/ascent_settings.preferences_pb"),
            workDir = File(context.filesDir, "backup"),
        )
    }
}

/** Why a backup can't be restored, or its manifest when it can. */
sealed interface RestoreCheck {
    data class Ok(val manifest: BackupManifest) : RestoreCheck
    /** Not a zip, or a damaged one, or a database that fails SQLite's check. */
    data object Corrupt : RestoreCheck
    /** A zip without a readable manifest or database. */
    data object NotABackup : RestoreCheck
    /** The database (or settings) doesn't match the manifest's SHA-256. */
    data object HashMismatch : RestoreCheck
    /** Made by a newer version of the app. */
    data class NewerSchema(val found: Int, val supported: Int) : RestoreCheck
    /** The file was fine, but preparing the restore failed (storage full, say). Nothing live changed. */
    data class Failed(val reason: String) : RestoreCheck
}

/**
 * The backup zip (PHASE5.md section 3, D6): `manifest.json`, `ascent.db` and
 * `datastore/<settings file>`. Pure file work: no Room, no DataStore.
 */
object BackupArchive {
    const val MANIFEST = "manifest.json"
    const val DATABASE = "ascent.db"
    const val DATASTORE = "datastore/ascent_settings.preferences_pb"
    const val MARKER = "restore.json"
    private const val STAGED_SETTINGS = "settings.preferences_pb"
    private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String = sha256(text.byteInputStream())

    fun write(out: OutputStream, manifest: BackupManifest, database: File, datastore: File?) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(BackupManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATABASE))
            database.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            if (datastore != null && datastore.exists()) {
                zip.putNextEntry(ZipEntry(DATASTORE))
                datastore.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Checks [zip] without touching any live file: a zip, a manifest, a schema this app
     * can migrate, matching hashes, and a database SQLite accepts at the stated version.
     */
    fun inspect(zip: File, supportedSchema: Int, scratch: File): RestoreCheck {
        scratch.mkdirs()
        val db = File(scratch, "inspect.db")
        try {
            ZipFile(zip).use { file ->
                val manifest = file.getEntry(MANIFEST)
                    ?.let { e -> file.getInputStream(e).use { String(it.readBytes()) } }
                    ?.let(BackupManifestCodec::decode)
                    ?: return RestoreCheck.NotABackup
                val dbEntry = file.getEntry(DATABASE) ?: return RestoreCheck.NotABackup
                if (manifest.schemaVersion < 1) return RestoreCheck.NotABackup
                if (manifest.schemaVersion > supportedSchema) return RestoreCheck.NewerSchema(manifest.schemaVersion, supportedSchema)
                file.getInputStream(dbEntry).use { input -> db.outputStream().use { input.copyTo(it) } }
                if (sha256(db) != manifest.dbSha256) return RestoreCheck.HashMismatch
                val settings = file.getEntry(DATASTORE)
                if (manifest.datastoreSha256 != null) {
                    if (settings == null) return RestoreCheck.NotABackup
                    if (file.getInputStream(settings).use(::sha256) != manifest.datastoreSha256) return RestoreCheck.HashMismatch
                }
                if (!databaseOk(db, manifest.schemaVersion)) return RestoreCheck.Corrupt
                return RestoreCheck.Ok(manifest)
            }
        } catch (_: ZipException) {
            return RestoreCheck.Corrupt
        } catch (_: IOException) {
            return RestoreCheck.Corrupt
        } finally {
            SIDE_FILES.forEach { File(db.path + it).delete() }
            db.delete()
        }
    }

    /** SQLite's own check, and Room's version stamp matches the manifest. */
    private fun databaseOk(db: File, schemaVersion: Int): Boolean =
        try {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                val check = sql.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                check == "ok" && sql.version == schemaVersion
            }
        } catch (_: SQLiteException) {
            false
        }

    /**
     * Extracts a checked backup into [staging] and writes the marker last, so a restore
     * that stops halfway is never applied. Nothing live is touched.
     */
    fun stage(zip: File, staging: File, fromFile: Boolean, manifest: BackupManifest) {
        staging.deleteRecursively()
        staging.mkdirs()
        ZipFile(zip).use { file ->
            file.getInputStream(file.getEntry(DATABASE)).use { input -> File(staging, DATABASE).outputStream().use { input.copyTo(it) } }
            file.getEntry(DATASTORE)?.let { e ->
                file.getInputStream(e).use { input -> File(staging, STAGED_SETTINGS).outputStream().use { input.copyTo(it) } }
            }
        }
        File(staging, MARKER).writeText(BackupManifestCodec.encodeMarker(RestoreMarker(fromFile, manifest)))
    }

    /**
     * Applies a staged restore (PHASE5.md section 3, step 4). Call before Room or
     * DataStore open: at app start, before Hilt builds anything. The old database's
     * WAL and SHM files go first, so they can never be replayed onto the new one.
     * Idempotent: if it stops halfway, the next start does it again.
     */
    fun applyPending(paths: BackupPaths): Boolean {
        val staging = paths.staging
        val marker = File(staging, MARKER)
        if (!marker.exists()) {
            if (staging.exists()) staging.deleteRecursively()
            return false
        }
        val db = File(staging, DATABASE)
        if (!db.exists()) {
            staging.deleteRecursively()
            return false
        }
        SIDE_FILES.forEach { File(paths.database.path + it).delete() }
        replace(db, paths.database)
        File(staging, STAGED_SETTINGS).takeIf { it.exists() }?.let { replace(it, paths.datastore) }
        paths.workDir.mkdirs()
        marker.copyTo(paths.restoredMarker, overwrite = true)
        staging.deleteRecursively()
        return true
    }

    /** Copy beside the target, then rename over it. */
    private fun replace(source: File, target: File) {
        target.parentFile?.mkdirs()
        val next = File(target.path + ".restoring")
        source.copyTo(next, overwrite = true)
        if (!next.renameTo(target)) {
            target.delete()
            if (!next.renameTo(target)) throw IOException("Couldn't replace ${target.name}")
        }
    }
}
