package com.tirthankar.ascent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
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
class PlannerSessionTest {
    @get:Rule val folder = TemporaryFolder()
    private var game: TestGame? = null
    @After fun close() { game?.close() }
    private fun normal(target: Double = 40.0, scope: QuestScope = QuestScope.DAILY) =
        NewQuest("Push-ups", Realm.BODY, Stat.STR, Tier.TRIVIAL, target, "reps", scope)

    private fun focus(key: String = "mind_deep_work", scope: QuestScope = QuestScope.DAILY) =
        NewQuest("Focus on project", Realm.MIND, Stat.INT, Tier.TRIVIAL, 60.0, "min", scope, templateKey = key)

    @Test fun `daily focus cannot pause resume or change away from duration`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        for (key in listOf("mind_deep_work", "mind_phone_free_block")) {
            val q = g.quests.add(focus(key))!!
            val id = g.activities.start(questId = q)!!
            assertFalse(g.activities.control(id, "PAUSE"))
            assertFalse(g.activities.control(id, "RESUME"))
            assertFalse(g.activities.setMode(q, "CHECKBOX"))
            assertEquals("RUNNING", g.db.plannerDao().session(id)!!.state)
            g.sessionClock.millis += 60_000
            g.sessionClock.bootNumber++
            g.activities.snapshot()
            assertEquals("PAUSED", g.db.plannerDao().session(id)!!.state)
            assertFalse(g.activities.control(id, "RESUME"))
            assertTrue(g.activities.control(id, "DISCARD"))
        }
        val weekly = g.quests.add(focus(scope = QuestScope.WEEKLY))!!
        val session = g.activities.start(questId = weekly)!!
        assertTrue(g.activities.control(session, "PAUSE"))
        assertTrue(g.activities.control(session, "RESUME"))
    }

    @Test fun `another timer cannot implicitly pause running daily focus`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val ordinary = g.quests.add(normal())!!
        val other = g.activities.start(questId = ordinary)!!
        assertTrue(g.activities.control(other, "PAUSE"))
        val q = g.quests.add(focus())!!
        val id = g.activities.start(questId = q)!!
        assertFalse(g.activities.control(other, "RESUME"))
        val third = g.quests.add(normal())!!
        assertNull(g.activities.start(questId = third))
        assertEquals("RUNNING", g.db.plannerDao().session(id)!!.state)
        assertTrue(g.activities.control(id, "DISCARD"))
        assertTrue(g.activities.control(other, "RESUME"))
    }

    @Test fun `daily focus rejects partial sessions but accepts one full result`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(focus())!!
        val target = g.db.questDao().getById(q)!!.target
        val partial = g.activities.start(questId = q)!!
        g.sessionClock.millis += 60_000
        assertTrue(g.activities.control(partial, "FINISH"))
        assertFalse(g.activities.saveResult(partial, target / 2))
        assertEquals(0.0, g.db.questDao().getById(q)!!.actualProgress)
        assertEquals(QuestStatus.OPEN, g.db.questDao().getById(q)!!.status)
        assertTrue(g.activities.control(partial, "DISCARD"))
        val full = g.activities.start(questId = q, manual = true)!!
        assertTrue(g.activities.saveResult(full, target))
        assertFalse(g.activities.saveResult(full, target))
        assertEquals(target, g.db.questDao().getById(q)!!.actualProgress)
        assertEquals(QuestStatus.DONE, g.db.questDao().getById(q)!!.status)
        assertEquals(1, g.db.plannerDao().sessions().count { it.questId == q && it.state == "SAVED" })
    }

    @Test fun `plan writes pay once and concurrent retries cannot farm XP`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val tomorrow = g.gameDay.today().toEpochDay() + 1
        assertFalse(g.activities.saveTomorrow(tomorrow, emptyList()))
        assertFalse(g.activities.saveTomorrow(tomorrow, listOf(PlannedTask(" "))))
        (1..5).map { async { g.activities.saveTomorrow(tomorrow, listOf(PlannedTask("Read"))) } }.awaitAll()
        assertEquals(10L, g.db.playerDao().getPlayer()!!.totalXp)
        assertEquals(1, g.db.plannerDao().plans().size)
        assertEquals(1, g.db.habitDao().xpEvents().count { it.source == XpSource.PLAN_WRITTEN })
    }

    @Test fun `all items are required and normal quest XP remains separate`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        g.activities.saveTomorrow(g.gameDay.today().toEpochDay() + 1, listOf(PlannedTask("Exercise"), PlannedTask("Email")))
        g.time.advance(Duration.ofDays(1)); g.cycle.ensureUpToDate()
        val items = g.db.plannerDao().items()
        val q = g.quests.add(normal())!!
        assertTrue(g.activities.bind(items[0].id, q))
        assertFalse(g.activities.bind(items[1].id, q))
        assertTrue(g.quests.complete(q)) // Includes existing bridge and quick completion path.
        assertNull(g.db.plannerDao().plans().single().completedAt)
        val session = g.activities.start(itemId = items[1].id, manual = true)!!
        assertTrue(g.activities.saveResult(session, 1.0))
        assertFalse(g.activities.saveResult(session, 1.0))
        assertFalse(g.quests.undo(q))
        val log = g.db.habitDao().xpEvents()
        assertEquals(1, log.count { it.source == XpSource.PLAN_COMPLETED })
        assertEquals(40L, log.single { it.source == XpSource.PLAN_COMPLETED }.amount)
        assertEquals(1, log.count { it.source == XpSource.QUEST })
        assertEquals(log.sumOf { it.amount }, g.db.playerDao().getPlayer()!!.totalXp)
    }

    @Test fun `partial sessions accumulate and saved session retries do not duplicate results`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal())!!
        val first = g.activities.start(questId = q, manual = true)!!
        assertFalse(g.activities.saveResult(first, Double.NaN))
        assertFalse(g.activities.saveResult(first, 1.5))
        assertTrue(g.activities.saveResult(first, 25.0))
        assertFalse(g.activities.saveResult(first, 25.0))
        assertEquals(25.0, g.db.questDao().getById(q)!!.actualProgress)
        assertEquals(0L, g.db.playerDao().getPlayer()!!.totalXp)
        val second = g.activities.start(questId = q, manual = true)!!
        assertTrue(g.activities.saveResult(second, 15.0))
        assertEquals(40.0, g.db.questDao().getById(q)!!.actualProgress)
        assertEquals(QuestStatus.DONE, g.db.questDao().getById(q)!!.status)
        assertEquals(1, g.db.habitDao().xpEvents().count { it.source == XpSource.QUEST })
    }

    @Test fun `pause excludes elapsed time and starting another activity pauses the first`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal())!!
        val id = g.activities.start(questId = q)!!
        g.sessionClock.millis += 60_000
        assertTrue(g.activities.control(id, "PAUSE"))
        g.sessionClock.millis += 120_000
        assertEquals(60L, g.activities.seconds(g.db.plannerDao().session(id)!!))
        g.activities.control(id, "RESUME")
        g.sessionClock.millis += 30_000
        val another = g.quests.add(normal())!!
        g.activities.start(questId = another)
        assertEquals(90L, g.db.plannerDao().session(id)!!.activeSeconds)
        assertEquals(1, g.db.plannerDao().sessions().count { it.state == "RUNNING" })
        g.sessionClock.bootNumber++
        g.activities.snapshot()
        assertTrue(g.db.plannerDao().sessions().none { it.state == "RUNNING" })
    }

    @Test fun `expiry blocks late saves and does not carry progress into tomorrow`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal().copy(recurring = true))!!
        val id = g.activities.start(questId = q, manual = true)!!
        g.time.instant = g.gameDay.nextReset()
        assertFalse(g.activities.saveResult(id, 40.0))
        g.cycle.ensureUpToDate()
        assertEquals("EXPIRED", g.db.plannerDao().session(id)!!.state)
        val fresh = g.db.questDao().all().last { it.title == "Push-ups" }
        assertNotEquals(q, fresh.id)
        assertEquals(0.0, fresh.actualProgress)
    }

    @Test fun `plan expires at reset and editing the old tomorrow target is rejected`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val tomorrow = g.gameDay.today().toEpochDay() + 1
        g.activities.saveTomorrow(tomorrow, listOf(PlannedTask("Read")))
        g.time.instant = g.gameDay.nextReset()
        assertFalse(g.activities.saveTomorrow(tomorrow, listOf(PlannedTask("Different"))))
        g.cycle.ensureUpToDate()
        assertEquals("ACTIVE", g.db.plannerDao().plan(tomorrow)!!.state)
        val session = g.activities.start(itemId = g.db.plannerDao().items().single().id, manual = true)!!
        g.time.instant = g.gameDay.nextReset()
        assertFalse(g.activities.saveResult(session, 1.0))
        g.activities.snapshot()
        assertEquals("EXPIRED", g.db.plannerDao().plan(tomorrow)!!.state)
        assertEquals(10L, g.db.playerDao().getPlayer()!!.totalXp)
    }

    @Test fun `partial habit observations and corrections reflect recorded work`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal().copy(recurring = true))!!
        val first = g.activities.start(questId = q, manual = true)!!
        g.activities.saveResult(first, 10.0)
        assertTrue(g.activities.removeResult(first))
        assertEquals(0.0, g.db.questDao().getById(q)!!.actualProgress)
        val second = g.activities.start(questId = q, manual = true)!!
        g.activities.saveResult(second, 20.0)
        g.time.advance(Duration.ofDays(1)); g.cycle.ensureUpToDate()
        val series = g.db.questDao().getById(q)!!.seriesId!!
        assertEquals(0.5, g.db.habitDao().observations(series).single().x)
    }

    @Test fun `above target preserves actual results and undo removes contributions once`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal())!!
        val id = g.activities.start(questId = q, manual = true)!!
        assertTrue(g.activities.saveResult(id, 50.0))
        assertEquals(50.0, g.db.questDao().getById(q)!!.actualProgress)
        assertTrue(g.activities.removeResult(id))
        assertEquals(0.0, g.db.questDao().getById(q)!!.actualProgress)
        assertEquals(0L, g.db.playerDao().getPlayer()!!.totalXp)
        assertFalse(g.activities.removeResult(id))
    }

    @Test fun `normal weekly sessions keep their weekly deadline`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal(scope = QuestScope.WEEKLY))!!
        val session = g.activities.start(questId = q)!!
        g.time.advance(Duration.ofDays(1)); g.cycle.ensureUpToDate()
        assertTrue(g.activities.control(session, "FINISH"))
        assertTrue(g.activities.saveResult(session, 40.0))
    }

    @Test fun `standalone measured plan requires its full target and pays only the shared bonus`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        g.activities.saveTomorrow(g.gameDay.today().toEpochDay() + 1, listOf(PlannedTask("Push-ups", "COUNT", 40.0, "reps")))
        g.time.advance(Duration.ofDays(1)); g.cycle.ensureUpToDate()
        val item = g.db.plannerDao().items().single()
        val first = g.activities.start(itemId = item.id, manual = true)!!
        g.activities.saveResult(first, 25.0)
        assertEquals(10L, g.db.playerDao().getPlayer()!!.totalXp)
        val next = g.activities.start(itemId = item.id, manual = true)!!
        g.activities.saveResult(next, 20.0)
        assertEquals(45.0, g.db.plannerDao().item(item.id)!!.progress)
        assertEquals(50L, g.db.playerDao().getPlayer()!!.totalXp)
        assertFalse(g.activities.removeResult(first))
        assertEquals(0, g.db.habitDao().xpEvents().count { it.source == XpSource.QUEST })
    }

    @Test fun `concurrent result saves record one contribution and one reward`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal())!!
        val session = g.activities.start(questId = q, manual = true)!!
        val results = (1..5).map { async { g.activities.saveResult(session, 40.0) } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(40.0, g.db.questDao().getById(q)!!.actualProgress)
        assertEquals(1, g.db.habitDao().xpEvents().count { it.source == XpSource.QUEST })
    }

    @Test fun `partial work prevents target edits and linked quest removal`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal())!!
        val session = g.activities.start(questId = q, manual = true)!!
        g.activities.saveResult(session, 20.0)
        assertFalse(g.quests.edit(q, QuestEdit(target = 10.0)))
        assertFalse(g.activities.setMode(q, "CHECKBOX"))
        g.activities.saveTomorrow(g.gameDay.today().toEpochDay() + 1, listOf(PlannedTask("Work")))
        g.time.advance(Duration.ofDays(1)); g.cycle.ensureUpToDate()
        val newQuest = g.quests.add(normal())!!
        val item = g.db.plannerDao().items().single()
        assertTrue(g.activities.bind(item.id, newQuest))
        assertFalse(g.quests.remove(newQuest))
    }

    @Test fun `Hyperfocus blocks generic resume and generic running blocks Hyperfocus`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        g.db.skillDao().upsertSkill(PlayerSkillEntity("hyperfocus", 0, null, 0, null))
        g.db.playerDao().upsertPlayer(g.db.playerDao().getPlayer()!!.copy(level = 20, mp = 500))
        val q = g.quests.add(NewQuest("Research", Realm.MIND, Stat.INT, Tier.EASY, 90.0, "min", QuestScope.DAILY))!!
        val session = g.activities.start(questId = q)!!
        assertIs<CastResult.Refused>(g.effects.cast("hyperfocus", CastTargetKind.QUEST, q))
        g.activities.control(session, "PAUSE")
        assertIs<CastResult.Refused>(g.effects.cast("hyperfocus", CastTargetKind.QUEST, q))
        val focusQuest = g.quests.add(NewQuest("Other research", Realm.MIND, Stat.INT, Tier.EASY, 90.0, "min", QuestScope.DAILY))!!
        assertIs<CastResult.Cast>(g.effects.cast("hyperfocus", CastTargetKind.QUEST, focusQuest))
        assertFalse(g.activities.control(session, "RESUME"))
        val other = g.quests.add(normal())!!
        assertNull(g.activities.start(questId = other))
    }

    @Test fun `changed reset time is shared by task sessions and planner eligibility`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        val q = g.quests.add(normal())!!
        val session = g.activities.start(questId = q, manual = true)!!
        g.settings.update { it.copy(resetTime = java.time.LocalTime.of(5, 30)) }
        g.activities.snapshot()
        assertEquals(g.gameDay.nextReset().toEpochMilli(), g.db.plannerDao().session(session)!!.endsAt)
        g.time.instant = g.gameDay.nextReset().minusMillis(1)
        assertTrue(g.activities.saveResult(session, 20.0))
        val second = g.activities.start(questId = q, manual = true)!!
        g.time.instant = g.gameDay.nextReset()
        assertFalse(g.activities.saveResult(second, 20.0))
    }

    @Test fun `neutral restore gaps expire plans without a completion payout`() = runTest {
        val g = TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }
        g.activities.saveTomorrow(g.gameDay.today().toEpochDay() + 1, listOf(PlannedTask("Read")))
        g.time.advance(Duration.ofDays(3))
        g.db.withTransaction { PlannerCoordinator(g.db, g.gameDay).reconcile(g.gameDay.today().toEpochDay()) }
        assertEquals("NEUTRAL", g.db.plannerDao().plans().single().state)
        assertEquals(10L, g.db.playerDao().getPlayer()!!.totalXp)
    }
}
