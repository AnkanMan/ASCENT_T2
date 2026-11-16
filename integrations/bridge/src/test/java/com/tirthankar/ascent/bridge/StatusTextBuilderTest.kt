package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.DailySnapshotEntity
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.model.Goal
import com.tirthankar.ascent.data.model.PlayerProfile
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.StatBlock
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.Tier
import com.tirthankar.ascent.data.db.ArcStatus
import com.tirthankar.ascent.data.db.BossStatus
import com.tirthankar.ascent.data.db.ChapterStatus
import com.tirthankar.ascent.data.db.GateSource
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.model.Arc
import com.tirthankar.ascent.data.model.Boss
import com.tirthankar.ascent.data.model.BossTask
import com.tirthankar.ascent.data.model.Chapter
import com.tirthankar.ascent.data.model.ChapterObjective
import com.tirthankar.ascent.data.model.Gate
import com.tirthankar.ascent.data.model.GateObjective
import com.tirthankar.ascent.engine.FinaleKind
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusTextBuilderTest {

    private val today = LocalDate.of(2026, 11, 11)

    private val player = PlayerProfile(
        name = "Player", title = "The Unmeasured", rank = Rank.E, level = 10, totalXp = 9_000,
        hp = 180, mp = 120, freePoints = 0, skillPoints = 5, streakDays = 12, bestStreak = 20,
        stats = StatBlock(str = 12, agi = 11, int = 15, foc = 13, wis = 10, vit = 9),
        mastery = emptyMap(),
        difficulty = mapOf(Realm.BODY to 1.05, Realm.MIND to 0.98, Realm.SELF to 1.0),
    )

    private fun quest(id: String, title: String, realm: Realm, target: Double, unit: String, status: QuestStatus, source: QuestSource, goalId: Long? = null, scope: QuestScope = QuestScope.DAILY) =
        Quest(
            id = id.hashCode().toLong(), publicId = id, title = title, realm = realm, stat = Stat.STR, tier = Tier.NORMAL,
            target = target, unit = TargetUnit.fromLabel(unit), unitLabel = unit, scope = scope, isDaily = scope == QuestScope.DAILY,
            recurring = false, source = source, goalId = goalId, day = today.toEpochDay(), status = status,
            xpAwarded = 0, critical = false, canUndo = false,
        )

    @Test
    fun `status text snapshot`() {
        val text = StatusTextBuilder.build(
            StatusSnapshot(
                today = today,
                resetTime = LocalTime.of(4, 0),
                isRestDay = false,
                player = player,
                goals = listOf(Goal(12, "g12", GoalHorizon.WEEK, Realm.MIND, "Finish KPZ scaling figures", 3.0, "figures", 1.0, GoalStatus.ACTIVE, null)),
                todayQuests = listOf(
                    quest("q7f2a", "Push-ups", Realm.BODY, 40.0, "reps", QuestStatus.OPEN, QuestSource.TEMPLATE),
                    quest("q1b9c", "KPZ sweep", Realm.MIND, 90.0, "min", QuestStatus.DONE, QuestSource.CLAUDE, goalId = 12),
                ),
                weekQuests = listOf(quest("q9z8y", "Three strength sessions", Realm.BODY, 3.0, "sessions", QuestStatus.OPEN, QuestSource.MANUAL, scope = QuestScope.WEEKLY)),
                lastDays = listOf(
                    DailySnapshotEntity(
                        day = today.minusDays(1).toEpochDay(), level = 10, totalXp = 8_700,
                        str = 12, agi = 11, int = 15, foc = 13, wis = 10, vit = 9, hp = 180, mp = 164, streakDays = 12,
                        objectives = 5, completed = 4, bodyDone = 1, bodyTotal = 1, mindDone = 1, mindTotal = 2,
                        selfDone = 2, selfTotal = 2, dailyComplete = false, restDay = false,
                    ),
                ),
                notes = "Toda draft due in March.",
                request = "Plan my day",
            ),
        )
        assertEquals(
            """
            ASCENT STATUS · v4
            Date: 2026-11-11 (Wed) · resets 04:00 · rest day: no
            Player: Lv 10 · Rank E · XP 1309/1995 · streak 12 (best 20) · HP 180/250 · MP 120/164
            Stats: STR 12 · AGI 11 · INT 15 · FOC 13 · WIS 10 · VIT 9
            Class: none
            Powers: none
            Rank trial: none
            Difficulty: BODY 1.05 · MIND 0.98 · SELF 1.00

            Goals:
            - g12 · week · MIND · Finish KPZ scaling figures · 1/3 figures

            Today's quests:
            - q7f2a · daily · BODY · Push-ups · 0/40 reps · normal · open · template
            - q1b9c · daily · MIND · KPZ sweep · 90/90 min · normal · done · goal g12

            This week's quests:
            - q9z8y · weekly · BODY · Three strength sessions · 0/3 sessions · normal · open

            Side quests:
            - none

            Extras today: 0 (next pays 100%)

            Arcs:
            - none

            Gates:
            - none

            Bosses:
            - none

            Last 7 days:
            - 11-10 Tue · 4/5 · BODY 1/1 · MIND 1/2 · SELF 2/2

            Notes: Toda draft due in March.

            Request: Plan my day
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `v4 adds strength to repeating quests, the extras line and neutral days`() {
        val push = quest("q7f2a", "Push-ups", Realm.BODY, 40.0, "reps", QuestStatus.OPEN, QuestSource.TEMPLATE).copy(seriesId = "t:body_push_ups")
        val stretch = quest("qs7r2", "Stretch", Realm.BODY, 10.0, "min", QuestStatus.DONE, QuestSource.MANUAL)
            .copy(recurring = true, isDaily = false, seriesId = "r:qs7r2")
        val oneOff = quest("qe1a1", "Email the group", Realm.SELF, 1.0, "done", QuestStatus.DONE, QuestSource.MANUAL).copy(isDaily = false)
        val weekly = quest("q9z8y", "Three strength sessions", Realm.BODY, 3.0, "sessions", QuestStatus.OPEN, QuestSource.MANUAL, scope = QuestScope.WEEKLY)
            .copy(recurring = true, seriesId = "r:q9z8y")
        fun day(offset: Long, neutral: Boolean, rest: Boolean) = DailySnapshotEntity(
            day = today.minusDays(offset).toEpochDay(), level = 10, totalXp = 8_700,
            str = 12, agi = 11, int = 15, foc = 13, wis = 10, vit = 9, hp = 180, mp = 164, streakDays = 12,
            objectives = 0, completed = 0, bodyDone = 0, bodyTotal = 0, mindDone = 0, mindTotal = 0,
            selfDone = 0, selfTotal = 0, dailyComplete = false, restDay = rest, neutral = neutral,
        )
        val text = StatusTextBuilder.build(
            StatusSnapshot(
                today = today, resetTime = LocalTime.of(4, 0), isRestDay = false, player = player, goals = emptyList(),
                todayQuests = listOf(push, stretch, oneOff), weekQuests = listOf(weekly),
                lastDays = listOf(day(2, neutral = true, rest = true), day(1, neutral = false, rest = true)),
                notes = "", request = null,
                strengths = mapOf("t:body_push_ups" to 72, "r:qs7r2" to 23, "r:q9z8y" to 31),
                extras = ExtrasStatus(count = 4, nextPercent = 25),
            ),
        )
        assertTrue(text.startsWith("ASCENT STATUS · v4\n"), text)
        assertTrue("- q7f2a · daily · BODY · Push-ups · 0/40 reps · normal · open · template · strength 72%\n" in text, text)
        assertTrue("- qs7r2 · daily · BODY · Stretch · 10/10 min · normal · done · repeats · extra · strength 23%\n" in text, text)
        assertTrue("- qe1a1 · daily · SELF · Email the group · 1/1 done · normal · done · extra\n" in text, text)
        assertTrue("- q9z8y · weekly · BODY · Three strength sessions · 0/3 sessions · normal · open · repeats · strength 31%\n" in text, text)
        assertTrue("Side quests:\n- none\n\nExtras today: 4 (next pays 25%)\n\nArcs:" in text, text)
        assertTrue("- 11-09 Mon · 0/0 · BODY 0/0 · MIND 0/0 · SELF 0/0 · neutral\n" in text, text)
        assertTrue("- 11-10 Tue · 0/0 · BODY 0/0 · MIND 0/0 · SELF 0/0 · rest" in text, text)
    }

    @Test
    fun `v2 sections list side quests, arcs, gates and bosses with their ids`() {
        val side = quest("qs1d3", "Submit the travel form", Realm.SELF, 1.0, "done", QuestStatus.OPEN, QuestSource.MANUAL)
            .copy(kind = QuestKind.SIDE, expiresDay = today.plusDays(2).toEpochDay())
        val arc = Arc(
            1, "a1", Realm.MIND, "The Rod Gas", ArcStatus.ACTIVE, "the_rod_gas", FinaleKind.BOSS, "The Draft",
            listOf(
                Chapter(1, 0, "Numerical current cumulants", ChapterStatus.DONE, emptyList(), 1_500),
                Chapter(2, 1, "BMFT prediction", ChapterStatus.ACTIVE, listOf(ChapterObjective(3, "Derive the BMFT prediction", 1.0, "derivations", 0.0)), 0),
            ),
        )
        val gate = Gate(
            3, "gt3", "The Literature Abyss", "", Realm.MIND, Rank.C, 7, false, GateStatus.OPEN, GateSource.BOARD,
            today.toEpochDay() - 2, today.toEpochDay() + 4,
            listOf(GateObjective(9, "Read a paper", 3.0, "papers", true, 1.0, 0.0, true)), 0.4, 0,
        )
        val boss = Boss(
            1, "b1", "The Draft", 1, LocalDate.of(2027, 3, 31).toEpochDay(), false, false, null, null, null, null,
            BossStatus.ACTIVE, listOf("Calculations", "Figures"),
            listOf(
                BossTask(1, "t1", 0, "Write up the BMFT calculation", 6.0, 600, true, true, false, false),
                BossTask(2, "t2", 1, "Make figure 3", 3.0, 300, false, true, false, false),
            ),
            900, 300, 1, false, false, false,
        )
        val text = StatusTextBuilder.build(
            StatusSnapshot(
                today = today, resetTime = LocalTime.of(4, 0), isRestDay = false, player = player, goals = emptyList(),
                todayQuests = emptyList(), weekQuests = emptyList(), lastDays = emptyList(), notes = "", request = null,
                sideQuests = listOf(side), arcs = listOf(arc), gates = listOf(gate), bosses = listOf(boss),
            ),
        )
        assertTrue("- qs1d3 · side · SELF · Submit the travel form · 0/1 done · normal · open · ends 11-13" in text, text)
        assertTrue("- a1 · MIND · The Rod Gas · active · chapter 2/2 BMFT prediction" in text, text)
        assertTrue("  - Derive the BMFT prediction · 0/1 derivations" in text, text)
        assertTrue("- gt3 · C · MIND · The Literature Abyss · open · 5 days left · 40%" in text, text)
        assertTrue("- b1 · The Draft · HP 300/900 · phase 2/2 Figures · deadline 2027-03-31" in text, text)
        assertTrue("  - t2 · phase 2 · Make figure 3 · 3 h · open" in text, text)
        assertTrue("t1" !in text) // finished tasks are left out

        // STATUS v3 golden lines: class, powers and the rank trial (PHASE4.md section 7).
        val v3 = StatusTextBuilder.build(
            StatusSnapshot(
                today = today, resetTime = LocalTime.of(4, 0), isRestDay = false, player = player, goals = emptyList(),
                todayQuests = emptyList(), weekQuests = emptyList(), lastDays = emptyList(), notes = "", request = null,
                classInfo = ClassStatus("Theorist", listOf(Stat.INT, Stat.WIS), polymath = false),
                powers = listOf(
                    PowerStatus("Hyperfocus", PowerState.Ready),
                    PowerStatus("Insight", PowerState.Charges(2, 2)),
                    PowerStatus("Limit Break", PowerState.Used("this week")),
                    PowerStatus("Second Wind", PowerState.Cooldown(5)),
                ),
                trial = TrialStatus(Rank.D, 4, 3, 6),
            ),
        )
        assertTrue("Class: Theorist (INT+WIS) · bonus +10% INT/WIS\n" in v3, v3)
        assertTrue("Powers: Hyperfocus ready · Insight 2/2 · Limit Break used this week · Second Wind cooldown 5 h\n" in v3, v3)
        assertTrue("Rank trial: D · open · 4 days left · 3/6\n" in v3, v3)
        val polymath = StatusTextBuilder.build(
            StatusSnapshot(
                today = today, resetTime = LocalTime.of(4, 0), isRestDay = false, player = player, goals = emptyList(),
                todayQuests = emptyList(), weekQuests = emptyList(), lastDays = emptyList(), notes = "", request = null,
                classInfo = ClassStatus("Polymath", emptyList(), polymath = true),
            ),
        )
        assertTrue("Class: Polymath · Resonance +5% per realm done today, max +15%" in polymath, polymath)

        // Mentor's Eye (A7): a 28-day history and the Review request; Insight's Stuck line.
        val review = StatusTextBuilder.build(
            StatusSnapshot(
                today = today, resetTime = LocalTime.of(4, 0), isRestDay = false, player = player, goals = emptyList(),
                todayQuests = emptyList(), weekQuests = emptyList(), lastDays = emptyList(), notes = "",
                request = PowerRequests.REVIEW, historyDays = 28,
            ),
        )
        assertTrue("Last 28 days:" in review && "Last 7 days:" !in review, review)
        assertTrue(review.endsWith("Request: Review"), review)
        assertEquals("Stuck: the fit won't converge", PowerRequests.stuck("  the fit\nwon't converge ").replace("  ", " "))
    }
}
