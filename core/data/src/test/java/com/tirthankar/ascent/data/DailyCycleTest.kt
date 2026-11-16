package com.tirthankar.ascent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.repo.NewGoal
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DailyCycleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var game: TestGame? = null
    private val wednesday = LocalDate.of(2026, 11, 11).toEpochDay()

    @After
    fun tearDown() {
        game?.close()
    }

    private suspend fun TestScope.newGame(): TestGame =
        TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }

    private suspend fun TestGame.completeDaily(day: Long) {
        db.questDao().getDailyObjectives(day).forEach { quests.complete(it.id) }
    }

    @Test
    fun `the Awakening issues today's Daily from templates`() = runTest {
        val g = newGame()
        val daily = g.db.questDao().getDailyObjectives(wednesday)
        assertTrue(daily.size in 3..5, "${daily.size} objectives")
        assertTrue(daily.all { it.isDaily && it.templateKey != null })
        assertEquals(1, daily.count { it.realm == Realm.BODY })
    }

    @Test
    fun `running the reset twice changes nothing`() = runTest {
        val g = newGame()
        val before = g.db.questDao().getDay(wednesday).size
        val again = g.cycle.ensureUpToDate()
        assertEquals(0, again.resolvedDays)
        assertFalse(again.issuedToday)
        assertEquals(before, g.db.questDao().getDay(wednesday).size)
    }

    @Test
    fun `a full day raises the streak and saves a snapshot`() = runTest {
        val g = newGame()
        g.completeDaily(wednesday)
        g.time.advance(Duration.ofDays(1))
        val result = g.cycle.ensureUpToDate()

        assertEquals(1, result.resolvedDays)
        assertTrue(result.issuedToday)
        val player = g.db.playerDao().getPlayer()!!
        assertEquals(1, player.streakDays)
        assertEquals(wednesday + 1, player.currentDay)
        val snapshot = assertNotNull(g.db.snapshotDao().get(wednesday))
        assertTrue(snapshot.dailyComplete)
        assertEquals(snapshot.objectives, snapshot.completed)
    }

    @Test
    fun `a missed day costs 15 HP, resets the streak and expires open quests`() = runTest {
        val g = newGame()
        val hp = g.db.playerDao().getPlayer()!!.hp
        g.time.advance(Duration.ofDays(1))
        g.cycle.ensureUpToDate()
        val player = g.db.playerDao().getPlayer()!!
        assertEquals(hp - 15, player.hp)
        assertEquals(0, player.streakDays)
        assertTrue(g.db.questDao().getDailyObjectives(wednesday).all { it.status == QuestStatus.EXPIRED })
    }

    @Test
    fun `missed days are caught up and trigger re-entry`() = runTest {
        val g = newGame()
        g.time.advance(Duration.ofDays(4))
        val result = g.cycle.ensureUpToDate()
        assertEquals(4, result.resolvedDays)
        val player = g.db.playerDao().getPlayer()!!
        assertEquals(4, player.consecutiveMisses)
        // Four zero days lower d a little; re-entry then takes 20% off on the third.
        val body = g.db.playerDao().getDifficulties().first { it.realm == Realm.BODY }
        assertTrue(body.multiplier < 0.8, "multiplier ${body.multiplier}")
        assertEquals(4, g.db.snapshotDao().getRange(wednesday, wednesday + 10).size, "one snapshot per day")
    }

    @Test
    fun `repeating quests come back the next day`() = runTest {
        val g = newGame()
        val id = g.quests.add(
            NewQuest("Stretch", Realm.BODY, Stat.AGI, Tier.EASY, 10.0, "min", QuestScope.DAILY, recurring = true),
        )!!
        g.quests.complete(id)
        g.time.advance(Duration.ofDays(1))
        g.cycle.ensureUpToDate()
        val tomorrow = g.db.questDao().getDay(wednesday + 1).filter { it.title == "Stretch" }
        assertEquals(1, tomorrow.size)
        assertEquals(QuestStatus.OPEN, tomorrow.single().status)
        assertTrue(tomorrow.single().recurring)
        assertEquals(QuestStatus.DONE, g.db.questDao().getById(id)!!.status, "history keeps the old one")
    }

    @Test
    fun `the week turns over at the Sunday reset`() = runTest {
        val g = newGame()
        val weekly = g.quests.add(
            NewQuest("Three strength sessions", Realm.BODY, Stat.STR, Tier.HARD, 3.0, "sessions", QuestScope.WEEKLY),
        )!!
        val goal = g.goals.add(NewGoal(GoalHorizon.WEEK, Realm.MIND, "Finish two figures", 2.0, "figures"))!!

        // Wed 10:00 → Sat 10:00: still the same week.
        g.time.advance(Duration.ofDays(3))
        g.cycle.ensureUpToDate()
        assertEquals(QuestStatus.OPEN, g.db.questDao().getById(weekly)!!.status)

        // Sun 10:00 (after the 04:00 reset): new week.
        g.time.advance(Duration.ofDays(1))
        g.cycle.ensureUpToDate()
        assertEquals(QuestStatus.EXPIRED, g.db.questDao().getById(weekly)!!.status)
        assertEquals(GoalStatus.CLOSED, g.db.goalDao().getById(goal)!!.status)
    }

    @Test
    fun `a rest-day Daily restores HP`() = runTest {
        val g = newGame()
        val player = g.db.playerDao().getPlayer()!!
        g.db.playerDao().upsertPlayer(player.copy(hp = 100))
        // Rest day defaults to Saturday: Wed → Sat.
        g.time.advance(Duration.ofDays(3))
        g.cycle.ensureUpToDate()
        val saturday = wednesday + 3
        // Friday's missed Daily left a penalty quest (Phase 3); finish it so only the rest day counts.
        g.db.questDao().getKindOnDay(com.tirthankar.ascent.data.db.QuestKind.PENALTY, saturday).forEach { g.quests.complete(it.id) }
        val hpBefore = g.db.playerDao().getPlayer()!!.hp
        g.completeDaily(saturday)
        g.time.advance(Duration.ofDays(1))
        g.cycle.ensureUpToDate()
        assertEquals(hpBefore + 30, g.db.playerDao().getPlayer()!!.hp)
        val bodyOnRestDay = g.db.questDao().getDailyObjectives(saturday).single { it.realm == Realm.BODY }
        assertTrue(bodyOnRestDay.templateKey in setOf("body_easy_walk", "body_stretch"))
    }
}
