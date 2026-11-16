package com.tirthankar.ascent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import com.tirthankar.ascent.engine.XpCurve
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class QuestRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var game: TestGame? = null

    @After
    fun tearDown() {
        game?.close()
    }

    private suspend fun TestScope.newGame(): TestGame =
        TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }

    private val pushUps = NewQuest(
        title = "Push-ups",
        realm = Realm.BODY,
        stat = Stat.STR,
        tier = Tier.NORMAL,
        target = 30.0,
        unitLabel = "reps",
        scope = QuestScope.DAILY,
    )

    @Test
    fun `completing pays XP and mastery and emits an event`() = runTest {
        val g = newGame()
        val seen = mutableListOf<GameEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { g.events.events.collect { seen += it } }

        val id = g.quests.add(pushUps)!!
        val before = g.players.profile.first()!!
        assertTrue(g.quests.complete(id))

        val quest = g.db.questDao().getById(id)!!
        val after = g.players.profile.first()!!
        assertEquals(QuestStatus.DONE, quest.status)
        assertTrue(quest.xpAwarded > 0)
        assertEquals(before.totalXp + quest.xpAwarded, after.totalXp)
        assertEquals(10, after.mastery.getValue(Stat.STR))
        assertEquals(quest.xpAwarded, (seen.single() as GameEvent.QuestCompleted).xp)
    }

    @Test
    fun `a quest completes only once`() = runTest {
        val g = newGame()
        val id = g.quests.add(pushUps)!!
        assertTrue(g.quests.complete(id))
        assertFalse(g.quests.complete(id))
    }

    @Test
    fun `undo reverses a completion exactly`() = runTest {
        val g = newGame()
        val id = g.quests.add(pushUps)!!
        val before = g.players.profile.first()!!
        g.quests.complete(id)
        assertTrue(g.quests.getByPublicId(g.db.questDao().getById(id)!!.publicId)!!.canUndo)
        assertTrue(g.quests.undo(id))
        val after = g.players.profile.first()!!
        assertEquals(before.totalXp, after.totalXp)
        assertEquals(before.stats, after.stats)
        assertEquals(before.mastery, after.mastery)
        assertEquals(QuestStatus.OPEN, g.db.questDao().getById(id)!!.status)
    }

    @Test
    fun `a level-up grants points and can't be undone`() = runTest {
        val g = newGame()
        val player = g.db.playerDao().getPlayer()!!
        g.db.playerDao().upsertPlayer(player.copy(totalXp = XpCurve.totalXpToReach(2) - 1))
        val seen = mutableListOf<GameEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { g.events.events.collect { seen += it } }

        val id = g.quests.add(pushUps)!!
        g.quests.complete(id)
        val after = g.db.playerDao().getPlayer()!!
        assertEquals(2, after.level)
        assertEquals(3, after.freePoints)
        assertEquals(1, after.skillPoints)
        assertEquals(GameEvent.LevelUp(1, 2, 3), seen.filterIsInstance<GameEvent.LevelUp>().single())
        assertFalse(g.quests.undo(id))
        assertEquals(2, g.db.playerDao().getPlayer()!!.level, "a level is never lost")
    }

    @Test
    fun `yesterday's quests can't be completed or undone`() = runTest {
        val g = newGame()
        val id = g.quests.add(pushUps)!!
        g.quests.complete(id)
        g.time.advance(java.time.Duration.ofDays(1))
        assertFalse(g.quests.undo(id))
        val stale = g.quests.add(pushUps)!!
        g.time.advance(java.time.Duration.ofDays(1))
        assertFalse(g.quests.complete(stale))
    }

    @Test
    fun `forbidden quests are refused`() = runTest {
        val g = newGame()
        assertNull(g.quests.add(pushUps.copy(title = "16h fast")))
    }

    @Test
    fun `manual quests don't join the Daily`() = runTest {
        val g = newGame()
        val id = g.quests.add(pushUps.copy(isDaily = true))!!
        val quest = g.quests.observeDay(LocalDate.of(2026, 11, 11)).first().first { it.id == id }
        assertTrue(quest.isDaily, "the flag passes through when a caller sets it")
        val manual = g.quests.add(pushUps)!!
        assertFalse(g.db.questDao().getById(manual)!!.isDaily)
    }

    @Test
    fun `free points are spent once`() = runTest {
        val g = newGame()
        val player = g.db.playerDao().getPlayer()!!
        g.db.playerDao().upsertPlayer(player.copy(freePoints = 3))
        assertTrue(g.players.allocatePoints(mapOf(Stat.STR to 2, Stat.VIT to 1)))
        assertFalse(g.players.allocatePoints(mapOf(Stat.STR to 1)))
        val profile = g.players.profile.first()!!
        assertEquals(12, profile.stats.str)
        assertEquals(11, profile.stats.vit)
        assertEquals(0, profile.freePoints)
    }
}
