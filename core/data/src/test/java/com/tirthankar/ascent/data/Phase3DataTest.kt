package com.tirthankar.ascent.data

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.db.ArcStatus
import com.tirthankar.ascent.data.db.BossStatus
import com.tirthankar.ascent.data.db.ChapterStatus
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.repo.BossException
import com.tirthankar.ascent.data.repo.BossRefusal
import com.tirthankar.ascent.data.repo.NewArc
import com.tirthankar.ascent.data.repo.NewBoss
import com.tirthankar.ascent.data.repo.NewBossPhase
import com.tirthankar.ascent.data.repo.NewBossTask
import com.tirthankar.ascent.data.repo.toStatBlock
import com.tirthankar.ascent.engine.ChapterTemplate
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.Rarity
import com.tirthankar.ascent.engine.Realm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class Phase3DataTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var game: TestGame? = null

    @After
    fun tearDown() {
        game?.close()
    }

    private suspend fun TestScope.newGame(): TestGame =
        TestGame(folder.newFolder(), backgroundScope).also { game = it; it.awaken() }

    private suspend fun TestGame.nextDay() {
        time.advance(Duration.ofDays(1))
        cycle.ensureUpToDate()
    }

    private suspend fun TestGame.xp() = db.playerDao().getPlayer()!!.totalXp

    @Test
    fun `the first run spawns a board, unlocks the first lore and checks achievements`() = runTest {
        val g = newGame()
        val offers = g.gates.currentList()
        assertTrue(offers.size in 2..4, "${offers.size} offers")
        assertTrue(offers.all { it.status == GateStatus.OFFERED })
        assertEquals(offers.size, offers.filter { !it.isRed }.map { it.realm }.toSet().size + offers.count { it.isRed })
        assertTrue(offers.all { it.objectives.size in 3..6 && it.objectives.all { o -> o.target > 0 } })
        val lore = g.lore.unlocked.first()
        assertTrue(lore.any { it.id == "lore_awakening" })
        // Starting stats are all equal, so Equipartition is earned at once.
        assertTrue(g.achievements.achievements.first().first { it.id == "equipartition" }.tier == 1)
        assertTrue("Equipartitioned" in g.achievements.titlesEarned.first())
        assertTrue(g.achievements.wear("Equipartitioned"))
        assertEquals("Equipartitioned", g.db.playerDao().getPlayer()!!.title)
        assertFalse(g.achievements.wear("A title nobody earned"))
    }

    @Test
    fun `a board spawns once per week`() = runTest {
        val g = newGame()
        val first = g.db.gateDao().getOffered().map { it.id }
        g.cycle.ensureUpToDate()
        assertEquals(first, g.db.gateDao().getOffered().map { it.id })
        // Wednesday → Sunday: the next board replaces the unaccepted offers.
        repeat(4) { g.nextDay() }
        val next = g.db.gateDao().getOffered()
        assertTrue(next.none { it.id in first })
        assertTrue(first.all { g.db.gateDao().getGate(it)!!.status == GateStatus.LAPSED })
    }

    @Test
    fun `a cleared gate pays XP and opens a chest`() = runTest {
        val g = newGame()
        val gate = g.gates.currentList().first { it.realm != Realm.BODY && !it.isRed }
        assertTrue(g.gates.accept(gate.id))
        val before = g.xp()
        val opened = g.gates.currentList().first { it.id == gate.id }
        assertEquals(GateStatus.OPEN, opened.status)
        opened.objectives.forEach { g.gates.log(it.id, it.target) }
        val closed = g.db.gateDao().getGate(gate.id)!!
        assertEquals(GateStatus.CLEARED, closed.status)
        assertTrue(closed.xpAwarded > 0)
        assertTrue(g.xp() >= before + closed.xpAwarded)
        assertTrue(g.inventory.items.first().any { it.source == "gate:${gate.publicId}" })
    }

    @Test
    fun `at most two gates open, and an expired gate below half breaks with a lore line`() = runTest {
        val g = newGame()
        val offers = g.gates.currentList().filter { !it.isRed }
        offers.take(2).forEach { assertTrue(g.gates.accept(it.id)) }
        if (offers.size > 2) assertFalse(g.gates.accept(offers[2].id))
        val gate = offers.first()
        repeat(8) { g.nextDay() }
        assertEquals(GateStatus.BROKEN, g.db.gateDao().getGate(gate.id)!!.status)
        assertTrue(g.lore.unlocked.first().any { it.id == "lore_gate_break_${gate.realm.name.lowercase()}" })
    }

    @Test
    fun `side quests - a balance quest at the reset, bounties up to three, 0_75 XP`() = runTest {
        val g = newGame()
        val today = g.gameDay.today()
        val side = g.quests.observeSide(today).first()
        assertEquals(1, side.size)
        assertEquals(QuestKind.SIDE, side.single().kind)
        assertNotNull(g.sideQuests.addBounty("Submit the travel reimbursement form"))
        assertNotNull(g.sideQuests.addBounty("Reply to the department email"))
        assertNull(g.sideQuests.addBounty("A fourth errand"))
        assertNull(g.sideQuests.addBounty("Skip dinner")) // forbidden either way
        val bounty = g.quests.observeSide(today).first().first { it.title.startsWith("Submit") }
        assertTrue(g.quests.complete(bounty.id))
        val done = g.db.questDao().getById(bounty.id)!!
        assertEquals(0.75, done.xpFactor)
        // Easy at level 1: 40 × 1.05 = 42 normally; × 0.75 = 31.5 → 32 (64 on a crit).
        assertTrue(done.xpAwarded == 32L || done.xpAwarded == 64L, "${done.xpAwarded}")
        // Side quests don't sit in today's Daily list.
        assertTrue(g.db.questDao().getDay(today.toEpochDay()).none { it.kind == QuestKind.SIDE })
    }

    @Test
    fun `completed balance side quest is not replaced until the next game day`() = runTest {
        val g = newGame()
        val today = g.gameDay.today()
        val original = g.db.questDao().getKindOnDay(QuestKind.SIDE, today.toEpochDay())
            .single { it.source == QuestSource.TEMPLATE }
        assertTrue(g.quests.complete(original.id))
        val xp = g.db.playerDao().getPlayer()!!.totalXp

        repeat(3) { g.cycle.ensureUpToDate() }

        val issued = g.db.questDao().getKindOnDay(QuestKind.SIDE, today.toEpochDay())
            .filter { it.source == QuestSource.TEMPLATE }
        assertEquals(listOf(original.id), issued.map { it.id })
        assertEquals(QuestStatus.DONE, issued.single().status)
        assertEquals(xp, g.db.playerDao().getPlayer()!!.totalXp)
        assertEquals(QuestStatus.DONE, g.quests.observeSide(today).first().single { it.id == original.id }.status)
        assertNotNull(g.sideQuests.addBounty("Pick up a parcel"))

        g.nextDay()
        val tomorrow = g.gameDay.today()
        val next = g.db.questDao().getKindOnDay(QuestKind.SIDE, tomorrow.toEpochDay())
            .single { it.source == QuestSource.TEMPLATE }
        assertTrue(next.id != original.id)
        assertEquals(QuestStatus.OPEN, next.status)
        assertTrue(g.quests.observeSide(tomorrow).first().none { it.id == original.id })
    }

    @Test
    fun `a missed Daily brings a penalty quest, worth 10 HP back, and a missed penalty costs 10`() = runTest {
        val g = newGame()
        val maxHp = DerivedStats.maxHp(g.db.playerDao().getAttributes().toStatBlock())
        g.nextDay() // Wednesday's Daily missed: −15 HP
        assertEquals(maxHp - 15, g.db.playerDao().getPlayer()!!.hp)
        val today = g.gameDay.today().toEpochDay()
        val penalty = g.db.questDao().getKindOnDay(QuestKind.PENALTY, today).single()
        assertTrue(g.quests.complete(penalty.id))
        assertEquals(maxHp - 5, g.db.playerDao().getPlayer()!!.hp)
        assertTrue(g.quests.undo(penalty.id))
        assertEquals(maxHp - 15, g.db.playerDao().getPlayer()!!.hp)
        g.nextDay() // Daily missed again and the penalty missed: −15 −10
        assertEquals(maxHp - 40, g.db.playerDao().getPlayer()!!.hp)
        // Only one penalty is open at a time.
        assertEquals(1, g.db.questDao().openPenalties())
    }

    @Test
    fun `a boss - phases unlock in order, defeat pays XP, an Epic drop, a title and lore`() = runTest {
        val g = newGame()
        val id = g.bosses.create(
            NewBoss(
                "The Draft",
                listOf(
                    NewBossPhase("Calculations", listOf(NewBossTask("Cumulant calculation", 1.0), NewBossTask("Check limits", 1.0))),
                    NewBossPhase("Figures", listOf(NewBossTask("Figure 3", 2.0))),
                ),
            ),
        ).getOrThrow()
        assertTrue(g.lore.addCustom(LoreTarget.BOSS, id, "defeat", "The Draft falls", "Months of hours, one manuscript."))
        val boss = g.bosses.active.first().single { it.id == id }
        assertEquals(400, boss.maxHp)
        val figure = boss.tasks.single { it.phase == 1 }
        assertFalse(figure.unlocked)
        assertFalse(g.bosses.hit(figure.id))
        boss.tasks.filter { it.phase == 0 }.forEach { assertTrue(g.bosses.hit(it.id)) }
        assertEquals(1, g.bosses.active.first().single { it.id == id }.currentPhase)
        val before = g.xp()
        val level = g.db.playerDao().getPlayer()!!.level
        assertTrue(g.bosses.hit(figure.id))
        val defeated = g.db.bossDao().getBoss(id)!!
        assertEquals(BossStatus.DEFEATED, defeated.status)
        // 1.5 × 400 × (1 + 0.05 L): 630 at level 1, 660 if the earlier hits levelled you up.
        val levelAfterTask = g.db.playerDao().getPlayer()!!.level.coerceAtMost(level + 1)
        assertTrue(
            defeated.xpAwarded in setOf(
                com.tirthankar.ascent.engine.BossRules.defeatXp(400, level, enraged = false),
                com.tirthankar.ascent.engine.BossRules.defeatXp(400, levelAfterTask, enraged = false),
            ),
            "${defeated.xpAwarded}",
        )
        assertTrue(g.xp() >= before + defeated.xpAwarded)
        val drop = g.inventory.items.first().single { it.source == "boss:${defeated.publicId}" }
        assertTrue(drop.rarity >= Rarity.EPIC)
        assertTrue("Resolver of The Draft" in g.achievements.titlesEarned.first())
        assertTrue(g.lore.unlocked.first().any { it.title == "The Draft falls" })
        assertTrue(g.lore.isCustomUnlocked(LoreTarget.BOSS, id, "defeat"))
        assertFalse(g.lore.addCustom(LoreTarget.BOSS, id, "defeat", "Again", "Frozen once unlocked."))
        // Peer Reviewed.
        assertTrue(g.bosses.markPaperSubmitted(id))
        assertEquals(1, g.achievements.achievements.first().first { it.id == "peer_reviewed" }.tier)
    }

    @Test
    fun `two bosses plus one raid`() = runTest {
        val g = newGame()
        repeat(2) { assertTrue(g.bosses.create(NewBoss("Boss $it", emptyList())).isSuccess) }
        val third = g.bosses.create(NewBoss("Boss 3", emptyList()))
        assertEquals(BossRefusal.LIMIT, (third.exceptionOrNull() as BossException).reason)
        assertTrue(g.bosses.create(NewBoss("The Thesis", emptyList(), isRaid = true)).isSuccess)
        assertTrue(g.bosses.create(NewBoss("Second raid", emptyList(), isRaid = true)).isFailure)
    }

    @Test
    fun `a trial beats your record or reopens in four weeks`() = runTest {
        val g = newGame()
        val id = g.bosses.createTrial("body_push_ups")!!
        val trial = g.db.bossDao().getBoss(id)!!
        assertEquals(15.0, trial.recordToBeat) // the Awakening max
        assertTrue(g.bosses.attemptTrial(id, 14.0))
        assertEquals(BossStatus.ACTIVE, g.db.bossDao().getBoss(id)!!.status)
        assertFalse(g.bosses.attemptTrial(id, 20.0)) // one attempt per 4 weeks
        g.time.advance(Duration.ofDays(28))
        g.cycle.ensureUpToDate()
        assertTrue(g.bosses.attemptTrial(id, 16.0))
        assertEquals(BossStatus.WON, g.db.bossDao().getBoss(id)!!.status)
        assertTrue(g.lore.unlocked.first().any { it.id == "lore_trial_won" })
    }

    @Test
    fun `arcs - one active per realm, chapters complete from logged progress and pay XP`() = runTest {
        val g = newGame()
        val arcId = g.arcs.startFromTemplate("quiet_hours")!!
        val second = g.arcs.create(NewArc(Realm.SELF, "Another Self arc", listOf(ChapterTemplate("Only chapter"))))!!
        val arcs = g.arcs.open()
        assertEquals(ArcStatus.ACTIVE, arcs.single { it.id == arcId }.status)
        assertEquals(ArcStatus.BACKLOG, arcs.single { it.id == second }.status)

        val chapter = arcs.single { it.id == arcId }.activeChapter!!
        val before = g.xp()
        g.arcs.log(chapter.objectives.single().id, 14.0)
        val after = g.arcs.open().single { it.id == arcId }
        assertEquals(ChapterStatus.DONE, after.chapters.first().status)
        assertEquals(ChapterStatus.ACTIVE, after.chapters[1].status)
        assertEquals(1_050L, after.chapters.first().xpAwarded) // 1,000 × 1.05
        assertTrue(g.xp() >= before + 1_050)
        assertTrue(g.lore.unlocked.first().any { it.id == "lore_chapter_done" })

        // A chapter with no objectives is completed by hand.
        assertTrue(g.arcs.activate(second))
        val only = g.arcs.open().single { it.id == second }.activeChapter!!
        assertTrue(g.arcs.completeChapter(only.id))
        assertEquals(null, g.arcs.open().firstOrNull { it.id == second })
        assertEquals(ArcStatus.DONE, g.db.arcDao().getArc(second)!!.status)
    }

    @Test
    fun `gear - equip, upgrade with Essence, dismantle`() = runTest {
        val g = newGame()
        val blade = g.db.withTransaction { g.inventory.grantTemplate("hamiltonian_blade", "test:blade") }!!
        val ring = g.db.withTransaction { g.inventory.grantTemplate("quiet_hours_ring", "test:ring") }!!
        assertNull(g.db.withTransaction { g.inventory.grantTemplate("hamiltonian_blade", "test:blade") }) // once per origin
        assertTrue(g.inventory.equip(blade.id))
        assertTrue(g.inventory.gearBonus().dormant.isEmpty()) // its free Insight works from Phase 4 (A12)
        assertFalse(g.inventory.dismantle(blade.id)) // equipped
        assertFalse(g.inventory.upgrade(ring.id)) // no Essence yet
        g.db.playerDao().upsertPlayer(g.db.playerDao().getPlayer()!!.copy(essence = 15))
        assertTrue(g.inventory.upgrade(ring.id))
        assertEquals(1, g.inventory.items.first().single { it.id == ring.id }.upgradeLevel)
        assertEquals(10, g.db.playerDao().getPlayer()!!.essence)
        assertTrue(g.inventory.dismantle(ring.id))
        assertEquals(20, g.db.playerDao().getPlayer()!!.essence) // Epic = 10
    }

    @Test
    fun `an emergency quest is issued inside its window once`() = runTest {
        val g = newGame()
        val next = g.sideQuests.nextEmergency(g.time.now())!!
        g.time.instant = next.plus(Duration.ofMinutes(5))
        g.cycle.ensureUpToDate()
        val issued = g.sideQuests.issueEmergencyIfDue()
        assertNotNull(issued)
        assertNull(g.sideQuests.issueEmergencyIfDue())
        val quest = g.db.questDao().getKindOnDay(QuestKind.EMERGENCY, g.gameDay.today().toEpochDay()).single()
        assertEquals(1.5, quest.xpFactor)
        assertEquals(QuestStatus.OPEN, quest.status)
        // After the 2-hour window it can't be completed.
        g.time.advance(Duration.ofHours(3))
        assertFalse(g.quests.complete(quest.id))
    }
}
