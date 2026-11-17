package com.tirthankar.ascent.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tirthankar.ascent.data.backup.BackupArchive
import com.tirthankar.ascent.data.backup.BackupResult
import com.tirthankar.ascent.data.backup.DirBackupFolder
import com.tirthankar.ascent.data.backup.RestoreCheck
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.Migrations
import com.tirthankar.ascent.data.db.PlayerSkillEntity
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.engine.BackupManifest
import com.tirthankar.ascent.engine.BackupManifestCodec
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.RestoreMarker
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PHASE5.md section 6, "Backup and restore" (Task 6). */
@RunWith(AndroidJUnit4::class)
class BackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AscentDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val games = mutableListOf<TestGame>()
    private val wednesday = LocalDate.of(2026, 11, 11).toEpochDay()

    @After
    fun tearDown() {
        games.forEach { it.close() }
    }

    private suspend fun TestScope.newGame(dbName: String? = null): TestGame =
        TestGame(folder.newFolder(), backgroundScope, dbName).also { games += it; it.awaken() }

    private fun mine(title: String) = NewQuest(title, Realm.SELF, Stat.WIS, Tier.EASY, 1.0, "done", QuestScope.DAILY)

    /** Every table, row for row, in rowid order. */
    private fun dump(db: AscentDatabase): Map<String, List<String>> {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.filter { it != "android_metadata" && it != "room_master_table" && !it.startsWith("sqlite_") }.sorted()
        return tables.associateWith { t ->
            sql.query("SELECT * FROM `$t` ORDER BY rowid").use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i -> if (c.isNull(i)) "∅" else c.getString(i) })
                }
            }
        }
    }

    private fun open(name: String): AscentDatabase =
        Room.databaseBuilder(context, AscentDatabase::class.java, name).addMigrations(*AscentDatabase.MIGRATIONS).build()

    private suspend fun TestGame.backupTo(dir: File): File {
        val result = backups.writeBackup(DirBackupFolder(dir), force = true)
        assertIs<BackupResult.Written>(result)
        return File(dir, result.name)
    }

    @Test
    fun `backup then restore reproduces every table row for row`() = runTest {
        val g = newGame("roundtrip.db")
        g.db.questDao().getDailyObjectives(wednesday).take(2).forEach { g.quests.complete(it.id) }
        g.quests.add(mine("Post the form"))
        g.activities.saveTomorrow(g.gameDay.today().toEpochDay() + 1, listOf(com.tirthankar.ascent.data.repo.PlannedTask("Read tomorrow")))
        val sessionQuest = g.quests.add(mine("Measured errand"))!!
        val sessionId = g.activities.start(questId = sessionQuest, manual = true)!!
        g.activities.saveResult(sessionId, 1.0)
        val studyId = g.study.start("Mathematics", "Review substitution", 1800)!!
        g.sessionClock.millis += 1800_000
        assertTrue(g.study.finish(studyId))
        assertTrue(g.study.submit(studyId, "Practiced substitution", "Try integration by parts"))
        val zip = g.backupTo(folder.newFolder("out"))
        val before = dump(g.db)
        val settings = g.paths.datastore.readBytes()

        // Life goes on after the backup.
        g.quests.complete(g.quests.add(mine("Something new"))!!)
        assertTrue(dump(g.db) != before)

        assertIs<RestoreCheck.Ok>(g.backups.inspectFile(zip))
        assertIs<RestoreCheck.Ok>(g.backups.confirmRestore())
        assertTrue(g.paths.preRestore.exists(), "a pre-restore backup always exists after a restore")
        g.db.close()

        assertTrue(BackupArchive.applyPending(g.paths))
        assertFalse(g.paths.staging.exists())
        assertTrue(g.paths.restoredMarker.exists())
        val restored = open("roundtrip.db")
        try {
            assertEquals(before, dump(restored))
        } finally {
            restored.close()
        }
        assertContentEquals(settings, g.paths.datastore.readBytes())
        // The pre-restore backup holds the state from just before.
        assertIs<RestoreCheck.Ok>(g.backups.inspectFile(g.paths.preRestore))
    }

    @Test
    fun `a Phase 3 backup restores and migrates`() = runTest {
        // A v2 (Phase 3) database, as an old backup would hold it.
        helper.createDatabase("phase3.db", 1).use { db ->
            db.execSQL(
                """
                INSERT INTO player (id, name, title, rank, level, totalXp, hp, mp, freePoints, skillPoints,
                    streakDays, bestStreak, consecutiveMisses, currentDay, lastIssuedDay, recoveryDay,
                    trainingDayIndex, createdAtMillis)
                VALUES (0, 'Kept', 'The Unmeasured', 'E', 12, 9000, 180, 120, 3, 5, 12, 12, 0, 20404, 20404, NULL, 4, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO quest (publicId, title, realm, stat, tier, target, unit, unitLabel, scope, isDaily, recurring,
                    source, templateKey, goalId, day, status, completedAtMillis, xpAwarded, critical, masteryAwarded,
                    levelAtCompletion, createdAtMillis)
                VALUES ('qdone', 'Push-ups', 'BODY', 'STR', 'NORMAL', 40, 'REPS', 'reps', 'DAILY', 1, 0,
                    'TEMPLATE', 'body_push_ups', NULL, 20403, 'DONE', 1, 126, 0, 10, 9, 0)
                """.trimIndent(),
            )
        }
        helper.runMigrationsAndValidate("phase3.db", 2, true, Migrations.MIGRATION_1_2).use { db ->
            db.execSQL("UPDATE player SET essence = 77")
            db.execSQL(
                """
                INSERT INTO gate (publicId, templateId, title, lore, realm, rank, days, isRed, status, source, boardWeek,
                    opensDay, closesDay, fraction, xpAwarded, createdAtMillis)
                VALUES ('g1', NULL, 'A Gate', 'Lore', 'BODY', 'E', 3, 0, 'CLEARED', 'BOARD', 20399, 20399, 20402, 1.0, 300, 0)
                """.trimIndent(),
            )
        }
        val old = context.getDatabasePath("phase3.db")
        val zip = folder.newFile("phase3-backup.zip")
        val manifest = BackupManifest(
            appVersion = "0.3", schemaVersion = 2, createdAtUtc = "2026-01-10T08:00:00Z", createdAtLocal = "2026-01-10T13:30:00",
            gameDay = "2026-01-10", rowCounts = mapOf("player" to 1L, "quest" to 1L, "gate" to 1L), dbSha256 = BackupArchive.sha256(old),
        )
        zip.outputStream().use { BackupArchive.write(it, manifest, old, null) }

        val g = newGame("target.db")
        assertEquals(RestoreCheck.Ok(manifest), g.backups.inspectFile(zip))
        assertIs<RestoreCheck.Ok>(g.backups.confirmRestore())
        g.db.close()
        assertTrue(BackupArchive.applyPending(g.paths))
        val restored = open("target.db")
        try {
            val player = restored.playerDao().getPlayer()!!
            assertEquals("Kept", player.name)
            assertEquals(9_000, player.totalXp)
            assertEquals(77, player.essence)
            assertNull(player.classId) // v3 column, added by the migration
            val quest = restored.questDao().getByPublicId("qdone")!!
            assertEquals(126L, quest.xpAwarded)
            assertEquals("t:body_push_ups", quest.seriesId) // v4 backfill
            assertEquals(1, restored.gateDao().clearedCount())
        } finally {
            restored.close()
        }
    }

    @Test
    fun `corrupt, foreign, tampered and newer backups are refused and nothing changes`() = runTest {
        val g = newGame("refuse.db")
        val good = g.backupTo(folder.newFolder("out"))
        val before = dump(g.db)

        val junk = folder.newFile("junk.zip").apply { writeBytes(ByteArray(512) { (it * 31).toByte() }) }
        assertEquals(RestoreCheck.Corrupt, g.backups.inspectFile(junk))

        val foreign = folder.newFile("photos.zip")
        ZipOutputStream(foreign.outputStream()).use { z -> z.putNextEntry(ZipEntry("cat.jpg")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry() }
        assertEquals(RestoreCheck.NotABackup, g.backups.inspectFile(foreign))

        val (manifest, dbBytes) = ZipFile(good).use { z ->
            BackupManifestCodec.decode(String(z.getInputStream(z.getEntry(BackupArchive.MANIFEST)).readBytes()))!! to
                z.getInputStream(z.getEntry(BackupArchive.DATABASE)).readBytes()
        }
        fun rezip(name: String, m: BackupManifest, bytes: ByteArray) = folder.newFile(name).also { f ->
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry(BackupArchive.MANIFEST)); z.write(BackupManifestCodec.encode(m).toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry(BackupArchive.DATABASE)); z.write(bytes); z.closeEntry()
            }
        }
        val tampered = dbBytes.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        assertEquals(RestoreCheck.HashMismatch, g.backups.inspectFile(rezip("tampered.zip", manifest.copy(datastoreSha256 = null), tampered)))

        val newer = rezip("newer.zip", manifest.copy(schemaVersion = AscentDatabase.VERSION + 1, datastoreSha256 = null), dbBytes)
        assertEquals(RestoreCheck.NewerSchema(AscentDatabase.VERSION + 1, AscentDatabase.VERSION), g.backups.inspectFile(newer))
        // Confirming a refused file does nothing either.
        assertIs<RestoreCheck.NewerSchema>(g.backups.confirmRestore())

        assertEquals(before, dump(g.db))
        assertFalse(g.paths.staging.exists())
        assertFalse(g.paths.preRestore.exists())
        assertFalse(BackupArchive.applyPending(g.paths))
    }

    @Test
    fun `the journal can be left out (D8), and a scheduled backup skips when nothing changed`() = runTest {
        val g = newGame("journal.db")
        g.db.skillDao().upsertSkill(PlayerSkillEntity("clarity", 0, null, 0, null))
        g.db.playerDao().upsertPlayer(g.db.playerDao().getPlayer()!!.copy(mp = 100))
        g.effects.clarity("clarity", "Moved the draft", "Figure 3", "Email the group")
        val out = folder.newFolder("out")

        g.backupPrefs.setExcludeJournal(true)
        val zip = g.backupTo(out)
        val extracted = folder.newFile("extracted.db")
        val manifest = ZipFile(zip).use { z ->
            z.getInputStream(z.getEntry(BackupArchive.DATABASE)).use { i -> extracted.outputStream().use { i.copyTo(it) } }
            BackupManifestCodec.decode(String(z.getInputStream(z.getEntry(BackupArchive.MANIFEST)).readBytes()))!!
        }
        assertTrue(manifest.journalExcluded)
        assertEquals(AscentDatabase.VERSION, manifest.schemaVersion)
        assertEquals(1L, manifest.rowCounts["player"])
        SQLiteDatabase.openDatabase(extracted.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT note FROM power_cast WHERE skillId = 'clarity'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0), "the journal isn't in the backup")
            }
        }
        // The live journal is untouched.
        assertTrue(g.effects.journal("clarity").single().second.contains("Figure 3"))

        // Scheduled runs: nothing changed since the backup above → nothing written.
        val dir = DirBackupFolder(folder.newFolder("scheduled"))
        assertEquals(BackupResult.Unchanged, g.backups.writeBackup(dir, force = false))
        assertTrue(dir.list().isEmpty())
        g.time.advance(Duration.ofMinutes(2))
        g.quests.complete(g.quests.add(mine("A change"))!!)
        assertIs<BackupResult.Written>(g.backups.writeBackup(dir, force = false))
        assertEquals(BackupResult.Unchanged, g.backups.writeBackup(dir, force = false))
        // Changing the journal setting changes what a backup holds.
        g.backupPrefs.setExcludeJournal(false)
        assertIs<BackupResult.Written>(g.backups.writeBackup(dir, force = false))
    }

    @Test
    fun `days between a backup and its restore are neutral (D7)`() = runTest {
        val g = newGame()
        g.db.questDao().getDailyObjectives(wednesday).forEach { g.quests.complete(it.id) }
        g.time.advance(Duration.ofDays(1))
        g.cycle.ensureUpToDate() // Thursday: the state the backup holds
        val thursday = wednesday + 1
        val atBackup = g.db.playerDao().getPlayer()!!
        assertEquals(1, atBackup.streakDays)

        // Three days later the backup is restored: the swap leaves this marker.
        g.time.advance(Duration.ofDays(3)) // Sunday
        val sunday = thursday + 3
        g.paths.workDir.mkdirs()
        g.paths.restoredMarker.writeText(
            BackupManifestCodec.encodeMarker(
                RestoreMarker(
                    fromFile = true,
                    manifest = BackupManifest(
                        appVersion = "1", schemaVersion = AscentDatabase.VERSION, createdAtUtc = "2026-11-12T10:00:00Z",
                        createdAtLocal = "2026-11-12T10:00", gameDay = "2026-11-12", rowCounts = mapOf("player" to 1L), dbSha256 = "x",
                    ),
                ),
            ),
        )
        g.cycle.ensureUpToDate()

        val player = g.db.playerDao().getPlayer()!!
        assertEquals(atBackup.streakDays, player.streakDays, "streak frozen")
        assertEquals(atBackup.hp, player.hp, "no HP lost")
        assertEquals(atBackup.consecutiveMisses, player.consecutiveMisses)
        assertEquals(sunday, player.currentDay)
        (thursday until sunday).forEach { d ->
            val snap = g.db.snapshotDao().get(d)!!
            assertTrue(snap.neutral && !snap.dailyComplete && snap.objectives == 0, "day $d is neutral")
        }
        assertTrue(g.db.questDao().getKindOnDay(QuestKind.PENALTY, sunday).isEmpty(), "no penalty quest")
        assertTrue(g.db.habitDao().allObservations().none { it.periodKey in thursday until sunday }, "no strength updates")
        assertTrue(g.db.questDao().getDailyObjectives(sunday).isNotEmpty(), "today's Daily is issued")
        val record = g.backupPrefs.current().lastRestore!!
        assertEquals(sunday, record.undoDay)
        assertTrue(record.countsOk)
        assertTrue("lore_conserved" in g.lore.unlocked.first().map { it.id })
        assertFalse(g.paths.restoredMarker.exists())

        // The next day is a normal one again.
        g.time.advance(Duration.ofDays(1))
        g.cycle.ensureUpToDate()
        assertFalse(g.db.snapshotDao().get(sunday)!!.neutral)
    }

    @Test
    fun `a half-staged restore is never applied`() = runTest {
        val g = newGame("half.db")
        g.paths.staging.mkdirs()
        File(g.paths.staging, BackupArchive.DATABASE).writeText("not yet")
        assertFalse(BackupArchive.applyPending(g.paths))
        assertFalse(g.paths.staging.exists())
        assertTrue(g.db.playerDao().getPlayer() != null)
    }
}
