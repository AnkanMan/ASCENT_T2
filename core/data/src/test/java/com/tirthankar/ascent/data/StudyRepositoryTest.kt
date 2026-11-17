package com.tirthankar.ascent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.db.*
import com.tirthankar.ascent.data.repo.*
import com.tirthankar.ascent.engine.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Duration
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class StudyRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private var game: TestGame? = null
    @After fun close() { game?.close() }
    private suspend fun TestGame.studyStart(seconds: Long = 1800, quest: Long? = null) =
        study.start("Mathematics", "Solve five problems", seconds, quest)
    private fun TestGame.advance(seconds: Long) { sessionClock.millis += seconds * 1000; time.advance(Duration.ofSeconds(seconds)) }
    private fun work(title: String = "Read textbook", minutes: Double = 30.0) =
        NewQuest(title, Realm.MIND, Stat.INT, Tier.TRIVIAL, minutes, "min", QuestScope.DAILY)

    @Test fun `invalid starts consume no attempts and linked target must fit`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        assertNull(g.studyStart(1799))
        assertNull(g.study.start(" ", "Review", 1800))
        assertNull(g.studyStart(1800, 99999))
        val q = g.quests.add(work(minutes = 60.0))!!
        assertNull(g.studyStart(1800, q))
        g.time.instant = g.gameDay.nextReset().minusSeconds(1799)
        assertNull(g.studyStart())
        assertEquals(0, g.study.snapshot().attempts)
    }

    @Test fun `three starts count discarded and early attempts and reset next game day`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val first = g.studyStart()!!
        assertTrue(g.study.discard(first))
        val second = g.studyStart()!!
        g.advance(60)
        assertTrue(g.study.finish(second))
        assertEquals("INCOMPLETE", g.db.studyDao().get(second)!!.state)
        val third = g.studyStart()!!
        g.advance(1800)
        assertTrue(g.study.finish(third))
        assertTrue(g.study.discard(third)) // Also discard an eligible result before claiming.
        assertFalse(g.study.submit(third, "Learned", ""))
        assertNull(g.studyStart())
        assertEquals(3, g.study.snapshot().attempts)
        assertEquals(0L, g.db.playerDao().getPlayer()!!.totalXp)
        g.time.instant = g.gameDay.nextReset()
        g.cycle.ensureUpToDate()
        assertEquals(0, g.study.snapshot().attempts)
        assertNotNull(g.studyStart())
    }

    @Test fun `actual time at sixty minutes pays thirty and above it pays one hundred`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val first = g.studyStart()!!
        g.advance(3600)
        assertTrue(g.study.finish(first))
        assertTrue(g.study.submit(first, "Learned substitutions", "Practice"))
        assertEquals(30L, g.db.studyDao().get(first)!!.xpAwarded)
        val second = g.studyStart()!!
        g.advance(3601)
        assertTrue(g.study.finish(second))
        assertTrue(g.study.submit(second, "Learned integration by parts", ""))
        assertEquals(100L, g.db.studyDao().get(second)!!.xpAwarded)
        assertEquals(130L, g.db.playerDao().getPlayer()!!.totalXp)
        assertEquals(2, g.db.habitDao().xpEvents().count { it.source == XpSource.STUDY })
    }

    @Test fun `concurrent starts produce one active attempt and claims cannot replay XP`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val starts = (1..5).map { async { g.studyStart() } }.awaitAll()
        assertEquals(1, starts.count { it != null })
        val id = starts.filterNotNull().single()
        g.advance(1800)
        assertTrue(g.study.finish(id))
        assertFalse(g.study.finish(id))
        assertFalse(g.study.submit(id, " ", ""))
        val claims = (1..5).map { async { g.study.submit(id, "Learned", "Next") } }.awaitAll()
        assertEquals(1, claims.count { it })
        assertEquals(30L, g.db.playerDao().getPlayer()!!.totalXp)
        assertEquals(1, g.db.habitDao().xpEvents().count { it.source == XpSource.STUDY })
    }

    @Test fun `a short attempt never completes a linked quest`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(work())!!
        val id = g.studyStart(3600, q)!!
        g.advance(1800)
        assertTrue(g.study.finish(id))
        assertFalse(g.study.submit(id, "Short session", ""))
        assertEquals(QuestStatus.OPEN, g.db.questDao().getById(q)!!.status)
        assertEquals(0.0, g.db.questDao().getById(q)!!.actualProgress)
        assertEquals("VOID", g.db.plannerDao().session(g.db.studyDao().get(id)!!.taskSessionId!!)!!.state)
        assertEquals(0L, g.db.playerDao().getPlayer()!!.totalXp)
    }

    @Test fun `linked completion pays each source once and updates the plan bonus`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        g.activities.saveTomorrow(g.gameDay.today().toEpochDay() + 1, listOf(PlannedTask("Study")))
        g.time.instant = g.gameDay.nextReset(); g.cycle.ensureUpToDate()
        val q = g.quests.add(work())!!
        assertTrue(g.activities.bind(g.db.plannerDao().items().single().id, q))
        val id = g.studyStart(3600, q)!!
        val marker = g.db.studyDao().get(id)!!.taskSessionId!!
        assertFalse(g.quests.complete(q))
        assertFalse(g.activities.control(marker, "PAUSE"))
        assertFalse(g.activities.control(marker, "DISCARD"))
        g.advance(3600)
        assertTrue(g.study.finish(id))
        assertFalse(g.activities.saveResult(marker, 60.0)) // Generic result form cannot bypass Study.
        assertTrue(g.study.submit(id, "Five problems solved", "Revision"))
        assertFalse(g.study.submit(id, "Replay", ""))
        assertEquals(QuestStatus.DONE, g.db.questDao().getById(q)!!.status)
        assertNotNull(g.db.plannerDao().plans().single().completedAt)
        val log = g.db.habitDao().xpEvents()
        assertEquals(1, log.count { it.source == XpSource.QUEST })
        assertEquals(1, log.count { it.source == XpSource.STUDY })
        assertEquals(1, log.count { it.source == XpSource.PLAN_COMPLETED })
        assertEquals(log.sumOf { it.amount }, g.db.playerDao().getPlayer()!!.totalXp)
    }

    @Test fun `same boot recovers time while reboot interrupts without XP or resume`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val id = g.studyStart()!!
        g.advance(120)
        assertEquals(120L, g.study.snapshot().elapsed)
        g.advance(60)
        val recreated = StudyRepository(g.db, g.gameDay, g.sessionClock, g.quests, g.skills, g.events)
        assertEquals(180L, recreated.snapshot().elapsed)
        g.sessionClock.bootNumber++
        g.sessionClock.millis = 500
        assertNull(g.study.snapshot().active)
        val saved = g.db.studyDao().get(id)!!
        assertEquals("INTERRUPTED", saved.state)
        assertEquals(180L, saved.activeSeconds)
        assertFalse(g.study.finish(id))
        assertFalse(g.study.submit(id, "Recovery", ""))
        assertEquals(1, g.study.snapshot().attempts)
        assertEquals(0L, saved.xpAwarded)
    }

    @Test fun `daily reset closes even a pending debrief without late rewards`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val id = g.studyStart()!!
        g.advance(1800)
        assertTrue(g.study.finish(id))
        g.time.instant = g.gameDay.nextReset()
        g.cycle.ensureUpToDate()
        assertFalse(g.study.submit(id, "Too late", ""))
        assertEquals("INCOMPLETE", g.db.studyDao().get(id)!!.state)
        assertEquals(0L, g.db.playerDao().getPlayer()!!.totalXp)
    }

    @Test fun `study blocks other timers and Hyperfocus and respects existing timers`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(work())!!
        val timer = g.activities.start(questId = q)!!
        assertNull(g.studyStart())
        assertTrue(g.activities.control(timer, "PAUSE"))
        val id = g.studyStart()!!
        assertFalse(g.activities.control(timer, "RESUME"))
        assertNull(g.activities.start(questId = g.quests.add(work("Other study"))!!))
        g.db.skillDao().upsertSkill(PlayerSkillEntity("hyperfocus", 0, null, 0, null))
        g.db.playerDao().upsertPlayer(g.db.playerDao().getPlayer()!!.copy(level = 20, mp = 500))
        val focus = g.quests.add(work("Focus block", 90.0))!!
        assertIs<CastResult.Refused>(g.effects.cast("hyperfocus", CastTargetKind.QUEST, focus))
        assertTrue(g.study.discard(id))
        assertIs<CastResult.Cast>(g.effects.cast("hyperfocus", CastTargetKind.QUEST, focus))
        assertNull(g.studyStart())
    }

    @Test fun `restore interrupts a linked attempt and closes its marker without reward`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(work())!!
        val id = g.studyStart(3600, q)!!
        g.advance(120)
        g.study.snapshot()
        g.paths.workDir.mkdirs()
        g.paths.restoredMarker.writeText(BackupManifestCodec.encodeMarker(RestoreMarker(
            fromFile = false,
            manifest = BackupManifest(appVersion = "1", schemaVersion = AscentDatabase.VERSION,
                createdAtUtc = g.time.now().toString(), createdAtLocal = "2026-11-11T10:00", gameDay = "2026-11-11",
                rowCounts = mapOf("player" to 1L), dbSha256 = "x"),
        )))
        assertTrue(g.restoreFinisher.finishIfPending())
        val attempt = g.db.studyDao().get(id)!!
        assertEquals("INTERRUPTED", attempt.state)
        assertEquals(120L, attempt.activeSeconds)
        assertEquals("VOID", g.db.plannerDao().session(attempt.taskSessionId!!)!!.state)
        assertEquals(0L, attempt.xpAwarded)
        assertEquals(QuestStatus.OPEN, g.db.questDao().getById(q)!!.status)
        assertFalse(g.study.submit(id, "Restored", ""))
    }

    @Test fun `an early finish confirmation cannot become a reward while the dialog is open`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val id = g.studyStart()!!
        g.advance(1805) // Target crossed while an early-finish confirmation was open.
        assertTrue(g.study.finish(id, early = true))
        assertEquals("INCOMPLETE", g.db.studyDao().get(id)!!.state)
        assertFalse(g.study.submit(id, "No claim", ""))
        assertEquals(0L, g.db.playerDao().getPlayer()!!.totalXp)
    }

    @Test fun `quote persists on refresh and changes on each new start`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val first = g.studyStart()!!
        val quote = g.db.studyDao().get(first)!!.quoteIndex
        repeat(3) { assertEquals(quote, g.study.snapshot().active!!.quoteIndex) }
        g.study.discard(first)
        val second = g.studyStart()!!
        assertNotEquals(quote, g.db.studyDao().get(second)!!.quoteIndex)
    }
}
