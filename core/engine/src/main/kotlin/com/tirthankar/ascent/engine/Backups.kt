package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** The player at backup time, for the restore confirmation and summary. */
@Serializable
data class BackupPlayer(val level: Int, val totalXp: Long, val streakDays: Int)

/**
 * `manifest.json` inside a backup zip (PHASE5.md section 3, D6). The database file is
 * the source of truth; the manifest lets a restore check it before touching anything.
 */
@Serializable
data class BackupManifest(
    val format: Int = FORMAT,
    val appVersion: String,
    /** Room's schema version of `ascent.db`; a newer one than the app's is refused. */
    val schemaVersion: Int,
    /** ISO-8601 instant. */
    val createdAtUtc: String,
    /** ISO-8601 local date-time on the phone that made it. */
    val createdAtLocal: String,
    /** ISO-8601 game day. */
    val gameDay: String,
    /** Rows per table in `ascent.db`. */
    val rowCounts: Map<String, Long>,
    val dbSha256: String,
    /** SHA-256 of the settings DataStore file, or null when there was none. */
    val datastoreSha256: String? = null,
    /** D8: Clarity's journal was left out. */
    val journalExcluded: Boolean = false,
    val player: BackupPlayer? = null,
) {
    companion object {
        const val FORMAT = 1
    }
}

/**
 * Written next to staged restore files, last, so a half-staged restore is never applied;
 * then moved aside by the swap so the first run after it can finish the restore.
 */
@Serializable
data class RestoreMarker(
    /** True for a restore from a file; false for Undo restore (the pre-restore backup). */
    val fromFile: Boolean,
    val manifest: BackupManifest,
)

object BackupManifestCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(manifest: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), manifest)

    /** Null for anything that isn't a readable manifest. */
    fun decode(text: String): BackupManifest? =
        try {
            json.decodeFromString(BackupManifest.serializer(), text)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun encodeMarker(marker: RestoreMarker): String = json.encodeToString(RestoreMarker.serializer(), marker)

    fun decodeMarker(text: String): RestoreMarker? =
        try {
            json.decodeFromString(RestoreMarker.serializer(), text)
        } catch (_: IllegalArgumentException) {
            null
        }
}

/** Backup file names: `ascent-backup-YYYYMMDD-HHMM.zip`, in the phone's local time. */
object BackupNames {
    private const val PREFIX = "ascent-backup-"
    private const val SUFFIX = ".zip"
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val pattern = Regex("""^ascent-backup-(\d{8}-\d{4})(?: \(\d+\))?\.zip$""")

    fun name(local: LocalDateTime): String = PREFIX + local.format(stamp) + SUFFIX

    /** When a backup file was made, or null for any other file (never deleted by retention). */
    fun parse(name: String): LocalDateTime? {
        val match = pattern.matchEntire(name) ?: return null
        return try {
            LocalDateTime.parse(match.groupValues[1], stamp)
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }

    /** The export file name (PHASE5.md section 3). */
    fun exportName(day: LocalDate): String = "ascent-export-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + SUFFIX
}

/**
 * Which backups to keep (PHASE5.md section 3): the newest file of each of the 14 most
 * recent days, plus the newest remaining file of each of the 12 most recent months.
 * Everything else that is a backup file goes; other files are never touched.
 */
object BackupRetention {
    const val DAILIES = 14
    const val MONTHLIES = 12

    fun keep(times: Collection<LocalDateTime>): Set<LocalDateTime> {
        val newestPerDay = times.groupBy { it.toLocalDate() }.mapValues { (_, v) -> v.max() }
        val dailies = newestPerDay.keys.sortedDescending().take(DAILIES).map { newestPerDay.getValue(it) }.toSet()
        val rest = times.filter { it !in dailies && it.toLocalDate() !in dailies.map(LocalDateTime::toLocalDate) }
        val newestPerMonth = rest.groupBy { YearMonth.from(it) }.mapValues { (_, v) -> v.max() }
        val monthlies = newestPerMonth.keys.sortedDescending().take(MONTHLIES).map { newestPerMonth.getValue(it) }
        return dailies + monthlies
    }

    /** The backup files in [names] to delete. */
    fun toDelete(names: Collection<String>): List<String> {
        val dated = names.mapNotNull { n -> BackupNames.parse(n)?.let { n to it } }
        val keep = keep(dated.map { it.second })
        // Two files from the same minute: keep one name.
        val kept = mutableSetOf<LocalDateTime>()
        return dated.sortedBy { it.first }.filter { (_, t) -> !(t in keep && kept.add(t)) }.map { it.first }
    }
}
