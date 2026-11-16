package com.tirthankar.ascent.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PHASE5.md section 6, "Strength". */
class HabitStrengthTest {

    private fun perfect(n: Int, period: SeriesPeriod) =
        HabitStrength.fold(List(n) { PeriodOutcome.Issued(1.0, 1.0) }, period)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")

    @Test
    fun `alpha_day is 1 - 0_2^(1 over 30)`() {
        assertEquals(1.0 - 0.2.pow(1.0 / 30.0), HabitStrength.ALPHA_DAY)
        assertClose(0.05223, HabitStrength.ALPHA_DAY, 1e-5)
        assertClose(0.3131, HabitStrength.ALPHA_WEEK, 1e-4)
        // Both remember about 18.64 days (τ = 30 / ln 5).
        assertClose(30.0 / kotlin.math.ln(5.0), HabitStrength.memoryDays(SeriesPeriod.DAY), 1e-9)
        assertClose(30.0 / kotlin.math.ln(5.0), HabitStrength.memoryDays(SeriesPeriod.WEEK), 1e-9)
    }

    @Test
    fun `a perfect daily record gives 80, 96 and 99_2 percent at 30, 60 and 90 days`() {
        assertClose(0.80, perfect(30, SeriesPeriod.DAY), 1e-9)
        assertClose(0.96, perfect(60, SeriesPeriod.DAY), 1e-9)
        assertClose(0.992, perfect(90, SeriesPeriod.DAY), 1e-9)
    }

    @Test
    fun `a perfect weekly record gives 31, 78 and 95 percent at 1, 4 and 8 weeks`() {
        assertClose(0.3131, perfect(1, SeriesPeriod.WEEK), 1e-4)
        assertClose(0.7773, perfect(4, SeriesPeriod.WEEK), 1e-4)
        assertClose(0.9504, perfect(8, SeriesPeriod.WEEK), 1e-4)
        // Exactly 1 − 0.2^(7k/30).
        listOf(1, 4, 8, 13).forEach { k ->
            assertClose(1.0 - 0.2.pow(7.0 * k / 30.0), perfect(k, SeriesPeriod.WEEK), 1e-12)
        }
    }

    @Test
    fun `one miss from a long perfect run multiplies S by 1 - alpha`() {
        val before = perfect(400, SeriesPeriod.DAY)
        val after = HabitStrength.next(before, 0.0, SeriesPeriod.DAY)
        assertClose((1.0 - HabitStrength.ALPHA_DAY) * before, after, 1e-12)
        assertClose(0.948, after, 1e-3)
        // Climbing back to 99% takes 31 perfect days, not 30.
        var s = after
        repeat(30) { s = HabitStrength.next(s, 1.0, SeriesPeriod.DAY) }
        assertTrue(s < 0.99)
        s = HabitStrength.next(s, 1.0, SeriesPeriod.DAY)
        assertTrue(s >= 0.99)
    }

    @Test
    fun `non-issued and neutral periods leave S unchanged`() {
        val base = List(10) { PeriodOutcome.Issued(1.0, 1.0) }
        val s = HabitStrength.fold(base, SeriesPeriod.DAY)
        val withGaps = base + PeriodOutcome.NotIssued + PeriodOutcome.Neutral + PeriodOutcome.NotIssued
        assertEquals(s, HabitStrength.fold(withGaps, SeriesPeriod.DAY))
        assertNull(HabitStrength.observe(PeriodOutcome.NotIssued))
        assertNull(HabitStrength.observe(PeriodOutcome.Neutral))
    }

    @Test
    fun `Streak Shield and Unbroken don't change strength (D3)`() {
        val base = List(20) { PeriodOutcome.Issued(1.0, 1.0) }
        val missed = HabitStrength.fold(base + PeriodOutcome.Issued(0.0, 1.0), SeriesPeriod.DAY)
        Forgiveness.entries.forEach { f ->
            assertEquals(missed, HabitStrength.fold(base + PeriodOutcome.Issued(0.0, 1.0, f), SeriesPeriod.DAY))
        }
        assertTrue(missed < HabitStrength.fold(base, SeriesPeriod.DAY))
    }

    @Test
    fun `partial credit is progress over target, capped at 1`() {
        assertEquals(0.5, HabitStrength.x(20.0, 40.0))
        assertEquals(1.0, HabitStrength.x(60.0, 40.0))
        assertEquals(0.0, HabitStrength.x(-3.0, 40.0))
        assertEquals(1.0, HabitStrength.x(1.0, 0.0))
        // Measured against that period's own target: 30 of 30 is full even after a raise to 40.
        val s = HabitStrength.fold(
            listOf(PeriodOutcome.Issued(30.0, 30.0), PeriodOutcome.Issued(30.0, 40.0)),
            SeriesPeriod.DAY,
        )
        val a = HabitStrength.ALPHA_DAY
        assertClose((1 - a) * a + a * 0.75, s, 1e-12)
    }

    @Test
    fun `editing a past period recomputes to the same value as a fresh computation`() {
        val random = Random(7)
        val history = (0L until 120L).map { StrengthObservation(20_000 + it, if (random.nextDouble() < 0.7) 1.0 else 0.0) }
        val edited = history.toMutableList().apply { this[40] = this[40].copy(x = 1.0 - this[40].x) }
        // Stored out of order, with the edit appended as a second row for the same key.
        val stored = history.shuffled(random) + edited[40]
        val fresh = HabitStrength.strength(edited, SeriesPeriod.DAY)
        assertEquals(fresh, HabitStrength.strength(stored, SeriesPeriod.DAY))
        // Recomputing twice gives the same answer.
        assertEquals(fresh, HabitStrength.strength(edited, SeriesPeriod.DAY))
    }

    @Test
    fun `percent rounds half-up and labels follow the shown number`() {
        assertEquals(73, HabitStrength.percent(0.725))
        assertEquals(72, HabitStrength.percent(0.7249))
        assertEquals(85, HabitStrength.percent(0.8495))
        assertEquals(StrengthLabel.BOUND, HabitStrength.label(HabitStrength.percent(0.8495)))
        assertEquals(StrengthLabel.UNSTABLE, HabitStrength.label(29))
        assertEquals(StrengthLabel.FORMING, HabitStrength.label(30))
        assertEquals(StrengthLabel.FORMING, HabitStrength.label(59))
        assertEquals(StrengthLabel.HOLDING, HabitStrength.label(60))
        assertEquals(StrengthLabel.HOLDING, HabitStrength.label(84))
        assertEquals(StrengthLabel.BOUND, HabitStrength.label(85))
        assertEquals(0, HabitStrength.percent(-1.0))
        assertEquals(100, HabitStrength.percent(1.0))
    }

    @Test
    fun `summary counts streaks, best streak and measuring`() {
        val xs = listOf(1.0, 1.0, 1.0, 0.0, 1.0, 0.5, 1.0, 1.0)
        val summary = HabitStrength.summarize(xs.mapIndexed { i, x -> StrengthObservation(100L + i, x) }, SeriesPeriod.DAY)
        assertEquals(2, summary.streak)
        assertEquals(3, summary.bestStreak)
        assertEquals(8, summary.observations)
        assertEquals(100L, summary.sinceKey)
        assertTrue(summary.measuring)
        val long = HabitStrength.summarize((0L until 30L).map { StrengthObservation(it, 1.0) }, SeriesPeriod.DAY)
        assertFalse(long.measuring)
        assertEquals(80, long.percent)
        assertEquals(StrengthLabel.HOLDING, long.label)
    }

    @Test
    fun `the trajectory ends at the strength and keeps the last 90 periods`() {
        val history = (0L until 120L).map { StrengthObservation(it, if (it % 5 == 0L) 0.0 else 1.0) }
        val line = HabitStrength.trajectory(history, SeriesPeriod.DAY)
        assertEquals(90, line.size)
        assertEquals(HabitStrength.strength(history, SeriesPeriod.DAY), line.last())
        assertEquals(listOf(HabitStrength.ALPHA_DAY), HabitStrength.trajectory(listOf(StrengthObservation(1, 1.0)), SeriesPeriod.DAY))
        assertTrue(HabitStrength.trajectory(emptyList(), SeriesPeriod.DAY).isEmpty())
    }

    @Test
    fun `Bound counts series at a shown 85 percent, and the realms they cover`() {
        val strengths = mapOf("a" to 0.86, "b" to 0.8495, "c" to 0.849, "d" to 0.99, "e" to 0.2)
        val realm = mapOf("a" to Realm.BODY, "b" to Realm.MIND, "c" to Realm.SELF, "d" to Realm.BODY, "e" to Realm.SELF)
        // b (84.95%) and c (84.9%) both show as 85% (half-up), so they count as Bound.
        assertEquals(4 to 3, AchievementMetrics.bound(strengths) { realm[it] })
        assertEquals(0 to 0, AchievementMetrics.bound(mapOf("x" to 0.844)) { Realm.BODY })
        assertEquals(2 to 1, AchievementMetrics.bound(mapOf("x" to 0.9, "y" to 0.95)) { Realm.MIND })
        // The shipped content links both achievements and the lore line.
        val content = TestContent.bundle
        assertEquals(AchievementMetrics.BOUND_HABITS, content.achievement("bound_state")!!.metric)
        assertEquals("Bound", content.achievement("bound_state")!!.rewards.single().title)
        assertEquals(listOf(3.0), content.achievement("bound_triplet")!!.thresholds)
        assertEquals(DropSource.CHEST_CB, content.achievement("bound_triplet")!!.rewards.single().chest)
        assertTrue(content.lore.entries.any { it.unlock.kind == LoreKinds.ACHIEVEMENT && it.unlock.achievement == "bound_state" })
        assertTrue(content.lore.entries.any { it.unlock.kind == LoreKinds.RESTORE && it.title == "Conserved" })
        assertTrue(ContentValidator.validate(content).isEmpty(), ContentValidator.validate(content).joinToString())
    }

    // ---- Period keys ----

    private fun clock(h: Int, m: Int, zone: String = "Asia/Kolkata") = GameClock(LocalTime.of(h, m), ZoneId.of(zone))

    private fun at(text: String, zone: String = "Asia/Kolkata"): Instant =
        java.time.LocalDateTime.parse(text).atZone(ZoneId.of(zone)).toInstant()

    @Test
    fun `day keys follow a 04_00 and a 05_30 reset`() {
        val d15 = LocalDate.of(2026, 11, 15).toEpochDay()
        assertEquals(d15 - 1, HabitPeriods.key(SeriesPeriod.DAY, clock(4, 0).gameDay(at("2026-11-15T03:59"))))
        assertEquals(d15, HabitPeriods.key(SeriesPeriod.DAY, clock(4, 0).gameDay(at("2026-11-15T04:00"))))
        assertEquals(d15 - 1, HabitPeriods.key(SeriesPeriod.DAY, clock(5, 30).gameDay(at("2026-11-15T05:29"))))
        assertEquals(d15, HabitPeriods.key(SeriesPeriod.DAY, clock(5, 30).gameDay(at("2026-11-15T05:30"))))
    }

    @Test
    fun `week keys turn over at the Sunday reset`() {
        // 2026-11-15 is a Sunday.
        val sunday = LocalDate.of(2026, 11, 15).toEpochDay()
        assertEquals(sunday - 7, HabitPeriods.key(SeriesPeriod.WEEK, clock(4, 0).gameDay(at("2026-11-15T03:59"))))
        assertEquals(sunday, HabitPeriods.key(SeriesPeriod.WEEK, clock(4, 0).gameDay(at("2026-11-15T04:00"))))
        assertEquals(sunday - 7, HabitPeriods.key(SeriesPeriod.WEEK, clock(5, 30).gameDay(at("2026-11-15T05:00"))))
        assertEquals(sunday, HabitPeriods.key(SeriesPeriod.WEEK, clock(5, 30).gameDay(at("2026-11-21T23:59"))))
        assertEquals(sunday, HabitPeriods.key(SeriesPeriod.WEEK, clock(5, 30).gameDay(at("2026-11-22T05:29"))))
        assertEquals(sunday + 7, HabitPeriods.key(SeriesPeriod.WEEK, clock(5, 30).gameDay(at("2026-11-22T05:30"))))
        assertEquals(LocalDate.of(2026, 11, 15), HabitPeriods.start(sunday))
    }

    @Test
    fun `a time-zone change keys by the local game day and never double-counts a day`() {
        val instant = Instant.parse("2026-11-14T23:00:00Z")
        // Kolkata: Sunday 04:30, a new day and a new week. London: still Saturday 23:00.
        val kolkata = clock(4, 0, "Asia/Kolkata").gameDay(instant)
        val london = clock(4, 0, "Europe/London").gameDay(instant)
        assertEquals(LocalDate.of(2026, 11, 15), kolkata)
        assertEquals(LocalDate.of(2026, 11, 14), london)
        assertEquals(kolkata.toEpochDay(), HabitPeriods.key(SeriesPeriod.WEEK, kolkata))
        assertEquals(kolkata.toEpochDay() - 7, HabitPeriods.key(SeriesPeriod.WEEK, london))
        // Flying west repeats a game day: the same key, so one observation (the last).
        val repeated = listOf(
            StrengthObservation(london.toEpochDay(), 0.0),
            StrengthObservation(london.toEpochDay(), 1.0),
        )
        assertEquals(HabitStrength.ALPHA_DAY, HabitStrength.strength(repeated, SeriesPeriod.DAY))
    }

    @Test
    fun `series ids name templates and recurring chains`() {
        assertEquals("t:body_push_ups", HabitSeries.forTemplate("body_push_ups"))
        assertEquals("r:q7f2a", HabitSeries.forRecurring("q7f2a"))
        assertEquals("body_push_ups", HabitSeries.templateKey("t:body_push_ups"))
        assertNull(HabitSeries.templateKey("r:q7f2a"))
    }
}
