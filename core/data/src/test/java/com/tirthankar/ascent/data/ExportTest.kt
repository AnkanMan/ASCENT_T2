package com.tirthankar.ascent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.backup.ExportService
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.engine.Csv
import com.tirthankar.ascent.engine.ExportColumns
import com.tirthankar.ascent.engine.ExportReadme
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PHASE5.md section 6, "Backup and restore": the CSV export (Task 8). */
@RunWith(AndroidJUnit4::class)
class ExportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var game: TestGame? = null

    @After
    fun tearDown() {
        game?.close()
    }

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, String(zip.readBytes(), Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `the export holds four CSV files and a README, quoted and in README order`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val wednesday = LocalDate.of(2026, 11, 11).toEpochDay()
        // A quest completed "before the XP log": history rebuilt from quest rows.
        val old = g.quests.add(NewQuest("Old errand", Realm.SELF, Stat.WIS, Tier.EASY, 1.0, "done", QuestScope.DAILY))!!
        g.db.questDao().update(
            g.db.questDao().getById(old)!!.copy(
                status = com.tirthankar.ascent.data.db.QuestStatus.DONE, completedAtMillis = 1, completedDay = wednesday, xpAwarded = 55,
            ),
        )
        g.db.playerDao().upsertPlayer(g.db.playerDao().getPlayer()!!.let { it.copy(totalXp = it.totalXp + 55) })

        val tricky = "Email \"the group\", then\nfile the form"
        g.quests.complete(g.quests.add(NewQuest(tricky, Realm.SELF, Stat.WIS, Tier.EASY, 1.0, "done", QuestScope.DAILY))!!)
        g.db.questDao().getDailyObjectives(wednesday).take(2).forEach { g.quests.complete(it.id) }
        g.time.advance(Duration.ofDays(1))
        g.cycle.ensureUpToDate()

        val out = ByteArrayOutputStream()
        ExportService(g.context, g.db, g.habits, g.gameDay, g.time).write(out)
        val files = unzip(out.toByteArray())
        assertEquals(setOf("quests.csv", "daily.csv", "habits.csv", "xp_log.csv", "README.txt"), files.keys)

        val readme = files.getValue(ExportColumns.README)
        ExportColumns.files.forEach { (name, columns) ->
            val rows = Csv.parse(files.getValue(name))
            assertEquals(columns, rows.first(), "$name header")
            assertEquals(columns, ExportReadme.columns(readme, name), "$name documented in order")
            assertTrue(rows.drop(1).all { it.size == columns.size }, "$name rows are complete")
        }

        val quests = Csv.parse(files.getValue(ExportColumns.QUESTS)).drop(1)
        val title = ExportColumns.quests.indexOf("title")
        assertTrue(quests.any { it[title] == tricky }, "commas, quotes and line breaks survive")
        assertTrue(files.getValue(ExportColumns.QUESTS).contains("\"Email \"\"the group\"\", then\nfile the form\""))

        val days = Csv.parse(files.getValue(ExportColumns.DAILY)).drop(1)
        assertEquals("2026-11-11", days.single()[0])
        assertEquals("false", days.single()[ExportColumns.daily.indexOf("neutral")])

        val habits = Csv.parse(files.getValue(ExportColumns.HABITS)).drop(1)
        assertTrue(habits.isNotEmpty())
        assertTrue(habits.all { it[2] == "2026-11-11" })

        // The XP log adds up to the player's XP: the old completion from history, the rest from the log.
        val xp = Csv.parse(files.getValue(ExportColumns.XP_LOG)).drop(1)
        assertEquals("from quest history", xp.first()[3])
        assertEquals(g.db.playerDao().getPlayer()!!.totalXp, xp.sumOf { it[2].toLong() })
    }
}
