package com.tirthankar.ascent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.db.CastStatus
import com.tirthankar.ascent.data.db.CastTargetKind
import com.tirthankar.ascent.data.db.GateKind
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.db.PlayerSkillEntity
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.BodyTargetEntity
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.data.repo.CastResult
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.Effect
import com.tirthankar.ascent.engine.LimitPeriod
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Respec
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import com.tirthankar.ascent.engine.UnlockCheck
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 4 data flows: skills, powers, classes, rank trials and the daily cycle hooks. */
@RunWith(AndroidJUnit4::class)
class Phase4DataTest {

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

    private suspend fun TestGame.player(): PlayerEntity = db.playerDao().getPlayer()!!

    private suspend fun TestGame.update(change: (PlayerEntity) -> PlayerEntity) = db.playerDao().upsertPlayer(change(player()))

    private suspend fun TestGame.setStats(vararg values: Pair<Stat, Int>) {
        val wanted = values.toMap()
        db.playerDao().upsertAttributes(db.playerDao().getAttributes().map { a -> wanted[a.stat]?.let { a.copy(value = it) } ?: a })
    }

    /** Test shortcut: hold a skill without paying for it. */
    private suspend fun TestGame.grant(vararg ids: String) = ids.forEach { db.skillDao().upsertSkill(PlayerSkillEntity(it, 0, null, 0, null)) }

    private suspend fun TestGame.nextDay() {
        time.advance(Duration.ofDays(1))
        cycle.ensureUpToDate()
    }

    private suspend fun TestGame.completeDaily(day: Long) {
        db.questDao().getDailyObjectives(day).forEach { quests.complete(it.id) }
    }

    private suspend fun TestGame.loreIds() = lore.unlocked.first().map { it.id }.toSet()

    // ---- Skills ----

    @Test
    fun `skill points come from level and bosses, and unlocks follow the rules`() = runTest {
        val g = newGame()
        g.setStats(Stat.STR to 10, Stat.AGI to 10)
        assertEquals(0, g.skills.unspentPoints())
        assertEquals(UnlockCheck.NoPoints, g.skills.checkUnlock("iron_body"))

        g.update { it.copy(level = 4) }
        assertEquals(2, g.skills.unspentPoints())
        assertEquals(UnlockCheck.Ok, g.skills.unlock("iron_body"))
        assertEquals(1, g.skills.unspentPoints())
        assertTrue("lore_skill_first" in g.loreIds())

        assertEquals(UnlockCheck.GateStat(20, 10), g.skills.checkUnlock("momentum"))
        g.setStats(Stat.AGI to 20) // A4: the higher of STR and AGI gates the Body tree
        assertEquals(UnlockCheck.Ok, g.skills.unlock("momentum"))
        assertEquals(0, g.skills.unspentPoints())
        assertEquals(UnlockCheck.NoPoints, g.skills.checkUnlock("light_feet"))
        assertEquals(UnlockCheck.MissingPrerequisite("light_feet"), g.skills.checkUnlock("second_wind"))
        assertEquals(UnlockCheck.Locked, g.skills.checkUnlock("superposition"))
        assertEquals(setOf("iron_body", "momentum"), g.skills.unlockedIds())
    }

    @Test
    fun `respec returns every point, keeps the use record and costs a token or Essence`() = runTest {
        val g = newGame()
        g.update { it.copy(level = 4) }
        g.skills.unlock("iron_body")
        g.skills.unlock("light_feet")
        g.db.skillDao().upsertSkill(g.db.skillDao().skill("light_feet")!!.copy(periodKey = "w20400", usesInPeriod = 1))

        assertNull(g.skills.respec(), "no token and no Essence")
        g.update { it.copy(essence = 60) }
        assertEquals(Respec(pointsReturned = 2, usesToken = false, essenceCost = 50), g.skills.respec())
        assertEquals(10, g.player().essence)
        assertTrue(g.skills.unlockedIds().isEmpty())
        assertEquals(2, g.skills.unspentPoints())
        val kept = g.db.skillDao().skill("light_feet")!!
        assertFalse(kept.unlocked)
        assertEquals(1, kept.usesInPeriod, "cooldowns and uses are not reset")
        assertTrue("lore_respec" in g.loreIds())

        g.skills.unlock("iron_body")
        g.update { it.copy(respecTokens = 1) }
        assertEquals(Respec(pointsReturned = 1, usesToken = true, essenceCost = 0), g.skills.respec())
        assertEquals(0, g.player().respecTokens)
        assertEquals(10, g.player().essence)
    }

    @Test
    fun `Bonds restores HP on a social quest and Literature Sense pays both stats`() = runTest {
        val g = newGame()
        g.grant("bonds", "literature_sense")
        // Far from the next level, so undo stays on offer.
        g.update { it.copy(hp = 100, level = 30) }
        val social = g.quests.add(
            NewQuest("Coffee with the group", Realm.SELF, Stat.WIS, Tier.EASY, 1.0, "times", QuestScope.DAILY, tags = setOf("Social", "made_up")),
        )!!
        assertEquals("social", g.db.questDao().getById(social)!!.tags, "unknown tags are dropped")
        assertTrue(g.quests.complete(social))
        assertEquals(110, g.player().hp)
        assertEquals(10, g.db.questDao().getById(social)!!.hpAwarded)

        val wisBefore = g.db.playerDao().getAttributes().first { it.stat == Stat.WIS }
        val paper = g.quests.add(NewQuest("Read the KPZ paper", Realm.MIND, Stat.INT, Tier.NORMAL, 1.0, "papers", QuestScope.DAILY))!!
        assertTrue(g.quests.complete(paper))
        val wisAfter = g.db.playerDao().getAttributes().first { it.stat == Stat.WIS }
        assertNotEquals(wisBefore, wisAfter, "a paper quest also pays WIS mastery")
        assertTrue(g.quests.undo(paper))
        assertEquals(wisBefore, g.db.playerDao().getAttributes().first { it.stat == Stat.WIS })
    }

    // ---- The daily cycle ----

    @Test
    fun `the Streak Shield covers the first miss of the week only, and Unbroken needs an unshielded miss`() = runTest {
        val g = newGame()
        g.grant("streak_shield", "unbroken")
        g.completeDaily(wednesday)
        g.nextDay() // Thursday: streak 1
        assertEquals(1, g.player().streakDays)

        g.nextDay() // Friday: Thursday missed, shielded
        val friday = wednesday + 2
        var player = g.player()
        assertEquals(1, player.streakDays)
        assertEquals(1, player.shieldsUsedWeek)
        assertTrue(player.lastMissShielded)
        assertTrue(g.db.questDao().getKindOnDay(QuestKind.PENALTY, friday).isEmpty(), "no penalty after a shielded miss")
        g.update { it.copy(mp = 200) }
        assertEquals(CastCheck.NoMissToRestore, g.powers.check("unbroken"))

        g.nextDay() // Saturday: Friday missed, no shield left this week
        player = g.player()
        assertEquals(0, player.streakDays)
        assertFalse(player.lastMissShielded)
        assertEquals(1, g.db.questDao().getKindOnDay(QuestKind.PENALTY, wednesday + 3).size)
        g.update { it.copy(mp = 200) }
        assertEquals(CastCheck.Ok, g.powers.check("unbroken"))
    }

    @Test
    fun `Recovery Aura makes a rest-day Daily restore 45 HP`() = runTest {
        val g = newGame()
        g.grant("recovery_aura")
        g.update { it.copy(hp = 100) }
        g.time.advance(Duration.ofDays(3)) // Saturday, the default rest day
        g.cycle.ensureUpToDate()
        val saturday = wednesday + 3
        g.db.questDao().getKindOnDay(QuestKind.PENALTY, saturday).forEach { g.quests.complete(it.id) }
        val hpBefore = g.player().hp
        g.completeDaily(saturday)
        g.nextDay()
        assertEquals(hpBefore + 45, g.player().hp)
    }

    // ---- Powers ----

    @Test
    fun `casts spend MP, keep to their limit and pay on the target`() = runTest {
        val g = newGame()
        g.grant("eigenstate_collapse")
        g.update { it.copy(mp = 200) }
        val daily = g.db.questDao().getDailyObjectives(wednesday)
        val mind = daily.first { it.realm != Realm.BODY }
        val other = daily.last { it.realm != Realm.BODY && it.id != mind.id }

        assertEquals(CastCheck.NeedsTarget, g.powers.check("eigenstate_collapse"))
        val options = g.powers.optionsFor(CastTargetKind.QUEST, mind.id)
        assertEquals(listOf("eigenstate_collapse"), options.map { it.skill.id })
        assertEquals(CastCheck.Ok, options.single().check)
        assertEquals(80, options.single().mpCost)
        assertEquals(CastResult.Cast(1, 80), g.powers.cast("eigenstate_collapse", CastTargetKind.QUEST, mind.id))
        assertEquals(120, g.player().mp)
        assertEquals(1, g.db.skillDao().skill("eigenstate_collapse")!!.usesInPeriod)
        assertEquals(
            CastResult.Refused(CastCheck.LimitReached(LimitPeriod.WEEK)),
            g.powers.cast("eigenstate_collapse", CastTargetKind.QUEST, other.id),
        )
        assertTrue(g.quests.complete(mind.id))
        assertEquals(CastStatus.COMPLETED, g.db.skillDao().castsOn(CastTargetKind.QUEST.name, mind.id, wednesday).single().status)
        assertTrue("lore_power_first_cast" in g.loreIds())
    }

    @Test
    fun `removing a cast's target the same day refunds the MP`() = runTest {
        val g = newGame()
        g.grant("eigenstate_collapse")
        g.update { it.copy(mp = 100) } // within max MP: a refund never goes above it
        val quest = g.db.questDao().getDailyObjectives(wednesday).first()
        assertIs<CastResult.Cast>(g.powers.cast("eigenstate_collapse", CastTargetKind.QUEST, quest.id))
        assertEquals(20, g.player().mp)
        assertTrue(g.quests.remove(quest.id))
        assertEquals(100, g.player().mp)
        assertEquals(CastStatus.REFUNDED, g.db.skillDao().castsOn(CastTargetKind.QUEST.name, quest.id, wednesday).single().status)
    }

    @Test
    fun `Overload doubles the day's Body XP and expires at the reset, and automatic skills can't be cast`() = runTest {
        val g = newGame()
        g.grant("overload", "stillness")
        g.update { it.copy(mp = 200, level = 30) }
        val body = g.db.questDao().getDailyObjectives(wednesday).single { it.realm == Realm.BODY }
        assertEquals(CastCheck.NotCastable, g.powers.check("stillness"), "Stillness triggers by itself")

        assertEquals(CastResult.Cast(1, 0), g.powers.cast("overload"))
        assertTrue(g.quests.complete(body.id))
        val paid = g.db.questDao().getById(body.id)!!
        assertTrue(g.quests.undo(body.id))
        g.db.skillDao().activeCasts().forEach { g.db.skillDao().updateCast(it.copy(status = CastStatus.EXPIRED)) }
        assertTrue(g.quests.complete(body.id))
        val plain = g.db.questDao().getById(body.id)!!
        val ratio = paid.xpAwarded.toDouble() / plain.xpAwarded * (if (plain.critical) 2 else 1) / (if (paid.critical) 2 else 1)
        assertEquals(2.0, ratio, 0.05, "Overload pays ×2 on top of everything else")

        g.update { it.copy(mp = 200) }
        g.db.skillDao().upsertSkill(g.db.skillDao().skill("overload")!!.copy(periodKey = null, usesInPeriod = 0))
        g.powers.cast("overload")
        assertEquals(1, g.db.skillDao().activeCasts().size)
        g.nextDay()
        assertTrue(g.db.skillDao().activeCasts().isEmpty(), "casts expire at the reset")
    }

    // ---- Section H: the powers ----

    @Test
    fun `Second Wind swaps a Body daily for 15 minutes of mobility that still counts`() = runTest {
        val g = newGame()
        g.grant("second_wind")
        g.update { it.copy(mp = 100) }
        val body = g.db.questDao().getDailyObjectives(wednesday).single { it.realm == Realm.BODY }
        assertIs<CastResult.Cast>(g.effects.cast("second_wind", CastTargetKind.QUEST, body.id))
        val swapped = g.db.questDao().getById(body.id)!!
        assertEquals("body_mobility", swapped.templateKey)
        assertEquals(15.0, swapped.target)
        assertEquals(TargetUnit.MINUTES, swapped.unit)
        assertEquals(Tier.EASY, swapped.tier)
        assertTrue(swapped.isDaily, "it still counts as the day's Body objective")
        assertEquals(80, g.player().mp)
        assertEquals(CastStatus.COMPLETED, g.db.skillDao().castsOn(CastTargetKind.QUEST.name, body.id, wednesday).single().status)

        // Not on rest days: Saturday is the default.
        g.time.advance(Duration.ofDays(3))
        g.cycle.ensureUpToDate()
        g.update { it.copy(mp = 100) }
        val saturdayBody = g.db.questDao().getDailyObjectives(wednesday + 3).single { it.realm == Realm.BODY }
        assertEquals(CastResult.Refused(CastCheck.RestDay), g.effects.cast("second_wind", CastTargetKind.QUEST, saturdayBody.id))
    }

    @Test
    fun `Limit Break raises a Body quest to the weekly cap and pays three times`() = runTest {
        val g = newGame()
        g.grant("limit_break")
        g.update { it.copy(mp = 100, level = 30) }
        val body = g.db.questDao().getDailyObjectives(wednesday).single { it.realm == Realm.BODY && it.unit != TargetUnit.OTHER }
        val week = GameClock.weekStart(LocalDate.ofEpochDay(wednesday)).toEpochDay()
        val cap = body.target * 1.5
        g.db.playerDao().upsertBodyTargets(listOf(BodyTargetEntity(body.templateKey!!, week, cap, body.target)))

        val before = g.db.questDao().getById(body.id)!!
        assertTrue(g.quests.complete(body.id))
        val plain = g.db.questDao().getById(body.id)!!
        assertTrue(g.quests.undo(body.id))

        assertIs<CastResult.Cast>(g.effects.cast("limit_break", CastTargetKind.QUEST, body.id))
        val raised = g.db.questDao().getById(body.id)!!
        assertTrue(raised.target > before.target && raised.target <= cap + 1e-9, "${raised.target} within the cap $cap (A1)")
        assertTrue(g.quests.complete(body.id))
        val paid = g.db.questDao().getById(body.id)!!
        val ratio = paid.xpAwarded.toDouble() / plain.xpAwarded * (if (plain.critical) 2 else 1) / (if (paid.critical) 2 else 1)
        assertEquals(3.0, ratio, 0.1)
    }

    @Test
    fun `Clarity keeps the journal on the phone and adds up to three quests for tomorrow`() = runTest {
        val g = newGame()
        g.grant("clarity")
        g.update { it.copy(mp = 100) }
        val result = g.effects.clarity("clarity", "Figure 2 done", "The fit won't converge", "Fix the fit, draft the abstract and email Ana, extra thing")
        assertIs<CastResult.Cast>(result)
        val tomorrow = g.db.questDao().getDay(wednesday + 1)
        assertEquals(listOf("Fix the fit", "draft the abstract", "email Ana"), tomorrow.map { it.title })
        assertTrue(tomorrow.none { it.isDaily })
        assertEquals(listOf("Figure 2 done", "The fit won't converge", "Fix the fit, draft the abstract and email Ana, extra thing"), g.effects.journal("clarity").single().second)
        assertEquals(CastResult.Refused(CastCheck.LimitReached(LimitPeriod.DAY)), g.effects.clarity("clarity", "a", "b", "c"))
    }

    @Test
    fun `Insight charges grow with WIS, and Mentor's Eye casts once a week`() = runTest {
        val g = newGame()
        g.grant("insight", "mentors_eye")
        g.setStats(Stat.WIS to 40) // 1 + 40 / 20 = 3 charges
        g.update { it.copy(mp = 130) }
        repeat(3) { assertIs<CastResult.Cast>(g.effects.insight("insight")) }
        assertEquals(CastResult.Refused(CastCheck.LimitReached(LimitPeriod.DAY)), g.effects.insight("insight"))
        assertEquals(70, g.player().mp)
        assertIs<CastResult.Cast>(g.effects.cast("mentors_eye"))
        assertEquals(CastResult.Refused(CastCheck.LimitReached(LimitPeriod.WEEK)), g.effects.cast("mentors_eye"))
    }

    @Test
    fun `Unbroken turns yesterday's miss into a completion at half XP`() = runTest {
        val g = newGame()
        g.grant("unbroken")
        g.completeDaily(wednesday)
        g.nextDay() // Thursday: streak 1
        g.nextDay() // Friday: Thursday missed
        val friday = wednesday + 2
        val missedBefore = g.player()
        assertEquals(0, missedBefore.streakDays)
        assertEquals(1, g.db.questDao().getKindOnDay(QuestKind.PENALTY, friday).count { it.status == QuestStatus.OPEN })
        g.update { it.copy(mp = 100) }

        assertIs<CastResult.Cast>(g.effects.cast("unbroken"))
        val after = g.player()
        assertEquals(2, after.streakDays, "Wednesday's 1, plus Thursday restored")
        assertEquals(missedBefore.hp + 15, after.hp)
        assertTrue(after.totalXp > missedBefore.totalXp)
        assertTrue(g.db.snapshotDao().get(wednesday + 1)!!.dailyComplete)
        assertTrue(g.db.questDao().getDailyObjectives(wednesday + 1).all { it.status == QuestStatus.DONE })
        assertTrue(g.db.questDao().getKindOnDay(QuestKind.PENALTY, friday).none { it.status == QuestStatus.OPEN })
        assertEquals(CastCheck.LimitReached(LimitPeriod.MONTH), g.powers.check("unbroken"))
    }

    // ---- Section I: Focus Session and Hyperfocus ----

    private fun deepWork() = NewQuest("Deep work", Realm.MIND, Stat.INT, Tier.NORMAL, 60.0, "min", QuestScope.DAILY)

    @Test
    fun `a full Focus Session completes its quest at double XP and double INT mastery`() = runTest {
        val g = newGame()
        g.grant("hyperfocus")
        g.update { it.copy(mp = 100, level = 30) }
        val plainId = g.quests.add(deepWork())!!
        assertTrue(g.quests.complete(plainId))
        val plain = g.db.questDao().getById(plainId)!!

        val id = g.quests.add(deepWork())!!
        assertIs<CastResult.Cast>(g.effects.cast("hyperfocus", CastTargetKind.QUEST, id))
        val session = g.effects.focusSession.first()!!
        assertEquals(id, session.questId)
        assertIs<CastCheck.OnCooldown>(g.powers.check("hyperfocus", CastTargetKind.QUEST, plainId))

        g.time.advance(Duration.ofMinutes(91))
        assertTrue(g.effects.endFocus(session.castId)!!.full)
        val paid = g.db.questDao().getById(id)!!
        assertEquals(QuestStatus.DONE, paid.status)
        assertEquals(plain.masteryAwarded * 2, paid.masteryAwarded)
        val ratio = paid.xpAwarded.toDouble() / plain.xpAwarded * (if (plain.critical) 2 else 1) / (if (paid.critical) 2 else 1)
        assertEquals(2.0, ratio, 0.05)
        assertNull(g.effects.focusSession.first())
    }

    @Test
    fun `ending a Focus Session early counts its minutes without the bonus`() = runTest {
        val g = newGame()
        g.grant("hyperfocus")
        g.update { it.copy(mp = 100, level = 30) }
        val long = g.quests.add(deepWork())!!
        assertIs<CastResult.Cast>(g.effects.cast("hyperfocus", CastTargetKind.QUEST, long))
        g.time.advance(Duration.ofMinutes(40))
        val result = g.effects.endFocus(g.effects.focusSession.first()!!.castId)!!
        assertFalse(result.full)
        assertEquals(40, result.progressMinutes)
        assertEquals(QuestStatus.OPEN, g.db.questDao().getById(long)!!.status, "40 minutes don't cover a 60-minute quest")
        assertEquals(mapOf(wednesday to 40.0), g.powers.focusMinutes(wednesday..wednesday))
        assertEquals(70, g.player().mp, "Hyperfocus's 30 MP isn't refunded")
    }

    // ---- Section J: class skills ----

    @Test
    fun `First Principles turns a Mind daily into a named derivation`() = runTest {
        val g = newGame()
        g.grant("first_principles")
        val mind = g.db.questDao().getDailyObjectives(wednesday).first { it.realm == Realm.MIND }
        assertEquals(CastResult.Refused(CastCheck.NeedsTarget), g.effects.cast("first_principles", CastTargetKind.QUEST, mind.id, " "))
        assertIs<CastResult.Cast>(g.effects.cast("first_principles", CastTargetKind.QUEST, mind.id, "KPZ scaling relation"))
        val swapped = g.db.questDao().getById(mind.id)!!
        assertEquals("Derivation: KPZ scaling relation", swapped.title)
        assertEquals(mind.tier, swapped.tier)
        assertTrue(swapped.isDaily)
        assertEquals("derivation", swapped.tags)
    }

    @Test
    fun `Parallel Runs completes the shorter linked quest with the longer one`() = runTest {
        val g = newGame()
        g.grant("parallel_runs")
        val long = g.quests.add(deepWork())!!
        val short = g.quests.add(NewQuest("Sweep run", Realm.MIND, Stat.FOC, Tier.NORMAL, 30.0, "min", QuestScope.DAILY))!!
        val option = g.powers.optionsFor(CastTargetKind.QUEST, long).single { it.skill.id == "parallel_runs" }
        assertTrue(option.partners.any { it.first == short })
        assertIs<CastResult.Cast>(g.effects.cast("parallel_runs", CastTargetKind.QUEST, long, short.toString()))
        assertTrue(g.quests.complete(long))
        assertEquals(QuestStatus.DONE, g.db.questDao().getById(short)!!.status, "the longer one carries the other")
    }

    @Test
    fun `Stillness refills MP on a meditation quest and Long Walk pays INT mastery, once a day each`() = runTest {
        val g = newGame()
        g.grant("stillness", "long_walk")
        g.update { it.copy(mp = 0, level = 30) }
        val calm = g.quests.add(NewQuest("Breathe", Realm.SELF, Stat.VIT, Tier.EASY, 10.0, "min", QuestScope.DAILY, tags = setOf("meditation")))!!
        assertTrue(g.quests.complete(calm))
        val refilled = g.player().mp
        assertTrue(refilled > 0, "a quarter of max MP")
        val again = g.quests.add(NewQuest("Breathe again", Realm.SELF, Stat.VIT, Tier.EASY, 10.0, "min", QuestScope.DAILY, tags = setOf("meditation")))!!
        assertTrue(g.quests.complete(again))
        assertEquals(refilled, g.player().mp, "once a day")

        val intBefore = g.db.playerDao().getAttributes().first { it.stat == Stat.INT }
        val walk = g.quests.add(NewQuest("Long walk", Realm.BODY, Stat.AGI, Tier.EASY, 40.0, "min", QuestScope.DAILY, tags = setOf("walk")))!!
        assertTrue(g.quests.complete(walk))
        assertNotEquals(intBefore, g.db.playerDao().getAttributes().first { it.stat == Stat.INT }, "Normal INT mastery")
    }

    // ---- Classes ----

    @Test
    fun `Class Awakening at level 10 offers the top classes and grants the unique skill`() = runTest {
        val g = newGame()
        g.setStats(Stat.STR to 10, Stat.AGI to 10, Stat.INT to 30, Stat.FOC to 10, Stat.WIS to 28, Stat.VIT to 10)
        assertTrue(g.classes.choices().isEmpty(), "not before level 10")

        g.update { it.copy(level = 10) }
        val offered = g.classes.choices().map { it.classId }
        assertEquals(listOf("theorist", "computationalist"), offered)
        assertFalse(g.classes.choose("vanguard"))
        assertTrue(g.classes.choose("theorist"))

        assertEquals("theorist", g.player().classId)
        assertTrue(g.db.skillDao().skill("first_principles")!!.unlocked)
        assertTrue("lore_class_theorist" in g.loreIds())
        assertTrue(g.classes.choices().isEmpty())
        assertFalse(g.classes.choose("computationalist"), "no change without a rank-up")
        assertTrue(g.skills.effects().any { it is Effect.XpMult && it.stat == Stat.INT })
    }

    // ---- Rank trials ----

    @Test
    fun `a rank trial opens at the threshold outside the Gate limit, and passing ranks you up`() = runTest {
        val g = newGame()
        g.update { it.copy(level = 10, classId = "theorist") }
        g.cycle.ensureUpToDate()

        val trial = g.db.gateDao().openTrials().single()
        assertEquals(GateKind.RANK_TRIAL, trial.kind)
        assertEquals(Rank.D, trial.targetRank)
        assertEquals(1, trial.attempt)
        assertEquals(6L, trial.closesDay!! - trial.opensDay!!)
        val objectives = g.db.gateDao().objectives(trial.id)
        assertEquals(6, objectives.size)
        assertTrue(objectives.all { it.target > 0 })
        assertTrue(g.gates.currentList().none { it.id == trial.id })
        assertEquals(0, g.gates.openCount(), "doesn't count toward the two-open limit")

        val xp = g.player().totalXp
        objectives.forEach { g.db.gateDao().updateObjective(it.copy(logged = it.target)) }
        g.trials.closeCleared()

        val closed = g.db.gateDao().getGate(trial.id)!!
        assertEquals(GateStatus.CLEARED, closed.status)
        val player = g.player()
        assertEquals(Rank.D, player.rank)
        assertEquals(1, player.respecTokens)
        assertTrue(player.classRechoice)
        assertTrue(player.totalXp > xp)
        assertTrue("lore_rank_d" in g.loreIds())
        assertTrue(g.inventory.items.first().any { it.source == "gate:${trial.publicId}" })
        assertTrue(g.classes.choices().isNotEmpty(), "the class re-choice is offered")

        g.cycle.ensureUpToDate()
        assertTrue(g.db.gateDao().openTrials().isEmpty(), "C needs level 25")
    }

    @Test
    fun `a failed rank trial pays for what was cleared and waits seven days`() = runTest {
        val g = newGame()
        g.update { it.copy(level = 10) }
        g.cycle.ensureUpToDate()
        val trial = g.db.gateDao().openTrials().single()

        repeat(7) { g.nextDay() }
        val failed = g.db.gateDao().getGate(trial.id)!!
        assertEquals(GateStatus.FAILED, failed.status)
        val closedOn = trial.opensDay!! + 7
        assertEquals(closedOn + 7, failed.retryAt)
        assertEquals(Rank.E, g.player().rank)
        assertTrue("lore_rank_trial_fail" in g.loreIds())

        repeat(6) { g.nextDay() }
        assertTrue(g.db.gateDao().openTrials().isEmpty(), "still waiting")
        g.nextDay()
        val retry = g.db.gateDao().openTrials().single()
        assertEquals(2, retry.attempt)
    }
}
