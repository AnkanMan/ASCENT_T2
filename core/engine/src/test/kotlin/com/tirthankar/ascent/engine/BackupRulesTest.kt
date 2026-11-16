package com.tirthankar.ascent.engine

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PHASE5.md section 6, "Backup and restore": names, manifest and retention. */
class BackupRulesTest {

    private val start = LocalDateTime.of(2025, 10, 1, 4, 12)

    @Test
    fun `names round-trip and other files are ignored`() {
        val t = LocalDateTime.of(2026, 11, 16, 4, 7)
        assertEquals("ascent-backup-20261116-0407.zip", BackupNames.name(t))
        assertEquals(t, BackupNames.parse("ascent-backup-20261116-0407.zip"))
        assertEquals(t, BackupNames.parse("ascent-backup-20261116-0407 (1).zip"))
        assertNull(BackupNames.parse("notes.txt"))
        assertNull(BackupNames.parse("ascent-backup-20261340-0407.zip"))
        assertEquals("ascent-export-20261116.zip", BackupNames.exportName(LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun `retention keeps exactly 14 dailies plus 12 monthlies`() {
        val daily = (0L until 400L).map { start.plusDays(it) }
        val keep = BackupRetention.keep(daily)
        assertEquals(26, keep.size)
        val newest = daily.last()
        // The 14 newest days...
        (0L until 14L).forEach { assertTrue(newest.minusDays(it) in keep) }
        // ...and one per month for the 12 months before them, the newest in each.
        val older = keep.filter { it < newest.minusDays(13) }
        assertEquals(12, older.size)
        assertEquals(12, older.map { java.time.YearMonth.from(it) }.toSet().size)
        older.forEach { kept -> assertTrue(daily.none { it > kept && java.time.YearMonth.from(it) == java.time.YearMonth.from(kept) && it !in keep }) }
    }

    @Test
    fun `several backups in one day keep the newest, and short histories are kept whole`() {
        val day = (0L until 5L).map { start.plusMinutes(it * 30) }
        assertEquals(setOf(day.last()), BackupRetention.keep(day))
        val week = (0L until 7L).map { start.plusDays(it) }
        assertEquals(week.toSet(), BackupRetention.keep(week))
    }

    @Test
    fun `toDelete only names backup files`() {
        val names = (0L until 30L).map { BackupNames.name(start.plusDays(it)) } + "notes.txt" + "ascent-export-20251001.zip"
        val delete = BackupRetention.toDelete(names)
        assertEquals(30 - 14 - 1, delete.size) // 14 dailies, plus one for October's older days
        assertTrue("notes.txt" !in delete && "ascent-export-20251001.zip" !in delete)
    }

    @Test
    fun `the manifest round-trips and junk reads as null`() {
        val m = BackupManifest(
            appVersion = "1.0",
            schemaVersion = 4,
            createdAtUtc = "2026-11-16T04:07:00Z",
            createdAtLocal = "2026-11-16T09:37:00",
            gameDay = "2026-11-16",
            rowCounts = mapOf("quest" to 120L, "player" to 1L),
            dbSha256 = "ab".repeat(32),
            journalExcluded = true,
            player = BackupPlayer(23, 41_200, 12),
        )
        assertEquals(m, BackupManifestCodec.decode(BackupManifestCodec.encode(m)))
        assertNull(BackupManifestCodec.decode("not json"))
        assertNull(BackupManifestCodec.decode("""{"format":1}"""))
    }
}
