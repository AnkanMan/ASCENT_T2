package com.tirthankar.ascent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.db.HabitObservationEntity
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.CastTargetKind
import com.tirthankar.ascent.data.repo.CastResult
import com.tirthankar.ascent.data.repo.HabitRepository
import com.tirthankar.ascent.engine.GrindGuard
import kotlinx.coroutines.flow.first
import kotlin.test.assertIs
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.data.repo.QuestEdit
import com.tirthankar.ascent.engine.HabitSeries
import com.tirthankar.ascent.engine.HabitStrength
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 5 data: habit series ids on new quests, and the strength cache (Task 3). */
@RunWith(AndroidJUnit4::class)
class Phase5DataTest {

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

    private suspend fun TestGame.nextDay() {
        time.advance(Duration.ofDays(1))
        cycle.ensureUpToDate()
    }

    private fun mine(title: String, recurring: Boolean = false, scope: QuestScope = QuestScope.DAILY) =
        NewQuest(title, Realm.BODY, Stat.AGI, Tier.EASY, 10.0, "min", scope, recurring = recurring)

    @Test
    fun `template dailies join their template's series, one-offs have none`() = runTest {
        val g = newGame()
        val daily = g.db.questDao().getDailyObjectives(wednesday)
        assertTrue(daily.isNotEmpty())
        daily.forEach { q ->
            assertEquals(QuestSource.TEMPLATE, q.source)
            assertEquals(HabitSeries.forTemplate(q.templateKey!!), q.seriesId)
        }
        val oneOff = g.db.questDao().getById(g.quests.add(mine("Email the group"))!!)!!
        assertNull(oneOff.seriesId)
        val bounty = g.db.questDao().getById(
            g.quests.add(mine("Post the form").copy(kind = QuestKind.SIDE, expiryDays = 3))!!,
        )!!
        assertNull(bounty.seriesId)
    }

    @Test
    fun `a recurring quest starts its own series and the daily copy continues it`() = runTest {
        val g = newGame()
        val id = g.quests.add(mine("Stretch", recurring = true))!!
        val first = g.db.questDao().getById(id)!!
        assertEquals(HabitSeries.forRecurring(first.publicId), first.seriesId)

        g.nextDay()
        val copy = g.db.questDao().getDay(wednesday + 1).single { it.title == "Stretch" && it.recurring }
        assertNotEquals(first.id, copy.id)
        assertEquals(first.seriesId, copy.seriesId)

        // A second recurring quest with the same title is its own series.
        val other = g.db.questDao().getById(g.quests.add(mine("Stretch", recurring = true))!!)!!
        assertNotEquals(first.seriesId, other.seriesId)
    }

    @Test
    fun `a weekly recurring quest keeps its series across the Sunday copy`() = runTest {
        val g = newGame()
        val id = g.quests.add(mine("Three strength sessions", recurring = true, scope = QuestScope.WEEKLY))!!
        val first = g.db.questDao().getById(id)!!
        repeat(4) { g.nextDay() } // Wednesday → Sunday
        val copy = g.db.questDao().getSeries(first.seriesId!!)
        assertEquals(2, copy.size)
        assertEquals(QuestScope.WEEKLY, copy.last().scope)
        assertEquals(com.tirthankar.ascent.engine.SeriesPeriod.WEEK, HabitRepository(g.db).period(first.seriesId!!))
    }

    @Test
    fun `editing a quest to recurring starts a series, and a title edit keeps it`() = runTest {
        val g = newGame()
        val id = g.quests.add(mine("Walk"))!!
        assertNull(g.db.questDao().getById(id)!!.seriesId)
        g.quests.edit(id, QuestEdit(recurring = true))
        val series = g.db.questDao().getById(id)!!.seriesId
        assertNotNull(series)
        g.quests.edit(id, QuestEdit(title = "Evening walk"))
        assertEquals(series, g.db.questDao().getById(id)!!.seriesId)
    }

    // ---- Task 4: wiring ----

    private suspend fun TestGame.player() = db.playerDao().getPlayer()!!

    private suspend fun TestGame.update(change: (com.tirthankar.ascent.data.db.PlayerEntity) -> com.tirthankar.ascent.data.db.PlayerEntity) =
        db.playerDao().upsertPlayer(change(player()))

    private suspend fun TestGame.grant(vararg ids: String) =
        ids.forEach { db.skillDao().upsertSkill(com.tirthankar.ascent.data.db.PlayerSkillEntity(it, 0, null, 0, null)) }

    private suspend fun TestGame.observed(day: Long): Map<String, Double> =
        db.habitDao().allObservations().filter { it.periodKey == day }.associate { it.seriesId to it.x }

    @Test
    fun `a completion counts at once, undo removes it, and the reset records every issued series`() = runTest {
        val g = newGame()
        g.update { it.copy(level = 30) }
        val daily = g.db.questDao().getDailyObjectives(wednesday)
        val first = daily.first()
        g.quests.complete(first.id)
        assertEquals(mapOf(first.seriesId!! to 1.0), g.observed(wednesday))
        assertEquals(HabitStrength.percent(HabitStrength.ALPHA_DAY), g.habits.strengths.first().getValue(first.seriesId!!).percent)

        assertTrue(g.quests.undo(first.id))
        assertTrue(g.observed(wednesday).isEmpty())
        assertNull(g.habits.strengths.first()[first.seriesId!!])

        g.quests.complete(first.id)
        g.nextDay()
        val recorded = g.observed(wednesday)
        assertEquals(daily.map { it.seriesId!! }.toSet(), recorded.keys)
        daily.forEach { q -> assertEquals(if (q.id == first.id) 1.0 else 0.0, recorded.getValue(q.seriesId!!), q.title) }
        // Thursday's quests continue the same template series.
        assertTrue(g.db.questDao().getDailyObjectives(wednesday + 1).all { it.seriesId!!.startsWith("t:") })
    }

    @Test
    fun `rest days record nothing for daily series`() = runTest {
        val g = newGame()
        repeat(3) { g.nextDay() } // Saturday, the default rest day
        val saturday = wednesday + 3
        g.db.questDao().getDailyObjectives(saturday).forEach { g.quests.complete(it.id) }
        assertTrue(g.observed(saturday).isEmpty())
        g.nextDay()
        assertTrue(g.observed(saturday).isEmpty())
        assertTrue(g.observed(saturday - 1).isNotEmpty(), "Friday counted")
    }

    @Test
    fun `a shielded miss still records 0 (D3)`() = runTest {
        val g = newGame()
        g.grant("streak_shield")
        g.db.questDao().getDailyObjectives(wednesday).forEach { g.quests.complete(it.id) }
        g.nextDay() // Thursday
        g.nextDay() // Friday: Thursday missed and shielded
        val thursday = wednesday + 1
        assertTrue(g.player().lastMissShielded)
        val recorded = g.observed(thursday)
        assertTrue(recorded.isNotEmpty() && recorded.values.all { it == 0.0 })
    }

    @Test
    fun `Unbroken restores the streak, not the strength (D3)`() = runTest {
        val g = newGame()
        g.grant("unbroken")
        g.db.questDao().getDailyObjectives(wednesday).forEach { g.quests.complete(it.id) }
        g.nextDay() // Thursday
        g.nextDay() // Friday: Thursday missed
        val thursday = wednesday + 1
        val before = g.observed(thursday)
        val strengthsBefore = g.habits.strengths.first()
        assertTrue(before.isNotEmpty() && before.values.all { it == 0.0 })
        g.update { it.copy(mp = 100) }
        assertIs<CastResult.Cast>(g.effects.cast("unbroken"))
        assertTrue(g.db.questDao().getDailyObjectives(thursday).all { it.status == com.tirthankar.ascent.data.db.QuestStatus.DONE })
        assertEquals(before, g.observed(thursday))
        assertEquals(strengthsBefore, g.habits.strengths.first())
        assertTrue(g.db.habitDao().xpEvents().any { it.source == com.tirthankar.ascent.data.db.XpSource.UNBROKEN && it.amount > 0 })
    }

    @Test
    fun `Second Wind gives the day to the Mobility series`() = runTest {
        val g = newGame()
        g.grant("second_wind")
        g.update { it.copy(mp = 100) }
        val body = g.db.questDao().getDailyObjectives(wednesday).single { it.realm == Realm.BODY }
        val original = body.seriesId!!
        assertIs<CastResult.Cast>(g.effects.cast("second_wind", CastTargetKind.QUEST, body.id))
        val swapped = g.db.questDao().getById(body.id)!!
        assertEquals("t:body_mobility", swapped.seriesId)
        g.quests.complete(body.id)
        g.nextDay()
        val recorded = g.observed(wednesday)
        assertEquals(1.0, recorded["t:body_mobility"])
        assertTrue(original !in recorded, "the swapped-out series isn't updated")
    }

    @Test
    fun `a weekly series records at the Sunday reset`() = runTest {
        val g = newGame()
        val id = g.quests.add(mine("Three strength sessions", recurring = true, scope = QuestScope.WEEKLY))!!
        val series = g.db.questDao().getById(id)!!.seriesId!!
        repeat(4) { g.nextDay() } // to Sunday: last week closes open
        val lastWeek = com.tirthankar.ascent.engine.GameClock.weekStart(LocalDate.ofEpochDay(wednesday)).toEpochDay()
        assertEquals(listOf(lastWeek to 0.0), g.db.habitDao().observations(series).map { it.periodKey to it.x })
        val copy = g.db.questDao().getSeries(series).last()
        g.quests.complete(copy.id)
        assertEquals(1.0, g.db.habitDao().observations(series).single { it.periodKey == copy.day }.x)
    }

    @Test
    fun `the guard pays less past the 3rd self-added quest, undo is exact, and it resets at the day boundary`() = runTest {
        val g = newGame()
        g.update { it.copy(level = 30) }
        val startXp = g.player().totalXp
        val ids = (1..6).map { g.quests.add(mine("Errand $it"))!! }
        ids.forEach { g.quests.complete(it) }
        val done = ids.map { g.db.questDao().getById(it)!! }
        assertEquals(listOf(1.0, 1.0, 1.0, 0.5, 0.25, 0.125), done.map { it.guardMultiplier })
        assertEquals(GrindGuard.scaleMastery(done[0].masteryAwarded, 0.5), done[3].masteryAwarded)
        // Template quests never use a slot.
        val template = g.db.questDao().getDailyObjectives(wednesday).first()
        g.quests.complete(template.id)
        assertEquals(1.0, g.db.questDao().getById(template.id)!!.guardMultiplier)

        var guard = g.quests.observeGuard(LocalDate.ofEpochDay(wednesday)).first()
        assertEquals(6, guard.extrasToday)
        assertEquals(3, guard.fullXp)
        assertEquals(6, guard.usedSlots)
        assertEquals(0.1, guard.nextMultiplier)

        // Undo the 4th: exactly its pay comes back, nobody else changes.
        val beforeUndo = g.player().totalXp
        assertTrue(g.quests.undo(done[3].id))
        assertEquals(beforeUndo - done[3].xpAwarded, g.player().totalXp)
        assertEquals(done.filter { it.id != done[3].id }.map { it.xpAwarded }, ids.filter { it != done[3].id }.map { g.db.questDao().getById(it)!!.xpAwarded })
        guard = g.quests.observeGuard(LocalDate.ofEpochDay(wednesday)).first()
        assertEquals(5, guard.usedSlots)
        // Redone, it takes the next slot (6th): 12.5%, less than before.
        g.quests.complete(done[3].id)
        assertEquals(0.125, g.db.questDao().getById(done[3].id)!!.guardMultiplier)

        // Every XP change is logged: the log sums to the total gained.
        assertEquals(g.player().totalXp - startXp, g.db.habitDao().xpEvents().sumOf { it.amount })
        assertTrue(g.db.habitDao().xpEvents().any { it.amount < 0 && it.refId == done[3].id })
        assertTrue(g.db.habitDao().xpEvents().single { it.refId == done[4].id }.multipliers.contains("guard=0.25"))

        // A new game day starts the guard over.
        g.nextDay()
        val tomorrow = g.quests.add(mine("Errand 7"))!!
        g.quests.complete(tomorrow)
        assertEquals(1.0, g.db.questDao().getById(tomorrow)!!.guardMultiplier)
    }

    @Test
    fun `Hard and Extreme quests use more slots`() = runTest {
        val g = newGame()
        g.update { it.copy(level = 30) }
        val normal = g.quests.add(mine("Errand"))!!
        val hard = g.quests.add(mine("Long errand").copy(tier = Tier.HARD))!!
        val extreme = g.quests.add(mine("Huge errand").copy(tier = Tier.EXTREME))!!
        g.quests.complete(normal) // slot 1
        g.quests.complete(hard) // slots 2–3
        g.quests.complete(extreme) // slots 4–6: pays m(6)
        assertEquals(1.0, g.db.questDao().getById(hard)!!.guardMultiplier)
        assertEquals(0.125, g.db.questDao().getById(extreme)!!.guardMultiplier)
    }

    @Test
    fun `Bound State and Bound Triplet unlock from strength, with the title and lore line`() = runTest {
        val g = newGame()
        val byRealm = g.db.questDao().getDailyObjectives(wednesday).groupBy { it.realm }.mapValues { it.value.first().seriesId!! }
        assertEquals(3, byRealm.size)
        suspend fun perfect(series: String) {
            (1L..40L).forEach { g.db.habitDao().upsertObservation(HabitObservationEntity(series, wednesday - 41 + it, 1.0, 1.0, 1.0, 0)) }
            g.habits.refresh(series)
        }
        perfect(byRealm.getValue(Realm.BODY))
        g.achievements.evaluate()
        suspend fun held() = g.db.progressDao().achievements().associate { it.achievementId to it.tier }
        assertEquals(1, held()["bound_state"])
        assertNull(held()["bound_triplet"])
        assertTrue(g.db.progressDao().hasTitle("Bound"))
        assertTrue("lore_bound_state" in g.lore.unlocked.first().map { it.id })

        perfect(byRealm.getValue(Realm.MIND))
        perfect(byRealm.getValue(Realm.SELF))
        g.achievements.evaluate()
        assertEquals(1, held()["bound_triplet"])
    }

    @Test
    fun `strength is computed on a cache miss, cached, and recomputed after invalidation`() = runTest {
        val g = newGame()
        val habits = HabitRepository(g.db)
        val dao = g.db.habitDao()
        val series = "t:body_push_ups"
        (0L until 30L).forEach { dao.upsertObservation(HabitObservationEntity(series, 20_000 + it, 1.0, 40.0, 40.0, 0)) }
        val summary = habits.summary(series)
        assertEquals(80, summary.percent)
        assertEquals(30, dao.cache(series)!!.observations)

        // Editing a past period: invalidate, then the recompute matches a fresh one.
        dao.upsertObservation(HabitObservationEntity(series, 20_010, 0.0, 40.0, 0.0, 0))
        habits.invalidate(series)
        val edited = habits.summary(series)
        val fresh = HabitStrength.strength(dao.observations(series).map { com.tirthankar.ascent.engine.StrengthObservation(it.periodKey, it.x) }, com.tirthankar.ascent.engine.SeriesPeriod.DAY)
        assertEquals(fresh, edited.strength)
        assertTrue(edited.strength < summary.strength)
        assertEquals(19, edited.streak)
        assertEquals(19, edited.bestStreak)
    }
}
