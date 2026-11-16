package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.model.Goal
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.engine.MuscleGroup
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.Tier
import org.junit.Test
import kotlin.test.assertEquals

class ProposalValidatorTest {

    private val today = 20_403L

    private fun quest(id: String, status: QuestStatus = QuestStatus.OPEN, day: Long = today) = Quest(
        id = 1, publicId = id, title = "Push-ups", realm = Realm.BODY, stat = Stat.STR, tier = Tier.NORMAL,
        target = 40.0, unit = TargetUnit.REPS, unitLabel = "reps", scope = QuestScope.DAILY, isDaily = true,
        recurring = false, source = QuestSource.TEMPLATE, goalId = null, day = day, status = status,
        xpAwarded = 0, critical = false, canUndo = false,
    )

    private val goal = Goal(7, "g7", GoalHorizon.WEEK, Realm.MIND, "Figures", 3.0, "figures", 0.0, GoalStatus.ACTIVE, null)

    private fun context(rest: Boolean = false, hard: Set<MuscleGroup> = emptySet()) = BridgeContext(
        todayEpochDay = today,
        weekStartEpochDay = today - 3,
        isRestDay = rest,
        quests = mapOf(
            "qopen" to quest("qopen"),
            "qdone" to quest("qdone", QuestStatus.DONE),
            "qold1" to quest("qold1", day = today - 1),
        ),
        goals = mapOf("g7" to goal),
        engineTarget = { if (it.key == "body_push_ups") 40.0 else if (it.key == "mind_deep_work") 60.0 else null },
        yesterdayHardMuscles = hard,
    )

    private fun card(action: BridgeAction, ctx: BridgeContext = context()) =
        ProposalValidator.validate(listOf(IndexedAction(0, action)), ctx).single()

    private fun addQuest(
        title: String = "Push-ups",
        realm: Realm = Realm.BODY,
        tier: Tier = Tier.NORMAL,
        target: Double? = 40.0,
        unit: String? = "reps",
        goal: String? = null,
    ) = BridgeAction.AddQuest(QuestScope.DAILY, realm, title, tier, target, unit, false, goal)

    @Test
    fun `a plain quest is ready and pays its template's stat`() {
        val c = card(addQuest())
        assertEquals(CardStatus.READY, c.status)
        assertEquals(Stat.STR, c.stat)
    }

    @Test
    fun `targets above the engine are clamped`() {
        val c = card(addQuest(target = 80.0))
        assertEquals(CardStatus.ADJUSTED, c.status)
        assertEquals(listOf(CardNote(NoteCode.TARGET_CLAMPED, 80.0, 40.0)), c.notes)
        assertEquals(40.0, (c.action as BridgeAction.AddQuest).target)
    }

    @Test
    fun `forbidden quests are blocked`() {
        val c = card(addQuest(title = "16h fast", realm = Realm.SELF, unit = null, target = null))
        assertEquals(CardStatus.BLOCKED, c.status)
        assertEquals(NoteCode.FORBIDDEN, c.notes.single().code)
    }

    @Test
    fun `sleep below seven hours is raised`() {
        val c = card(addQuest(title = "Sleep", realm = Realm.SELF, target = 6.0, unit = "h"))
        assertEquals(listOf(CardNote(NoteCode.SLEEP_FLOOR, 6.0, 7.0)), c.notes)
    }

    @Test
    fun `Body work on a rest day is eased to easy`() {
        val c = card(addQuest(), context(rest = true))
        assertEquals(Tier.EASY, (c.action as BridgeAction.AddQuest).tier)
        assertEquals(NoteCode.REST_DAY_EASED, c.notes.single().code)
    }

    @Test
    fun `hard work on yesterday's muscle group is eased`() {
        val c = card(addQuest(tier = Tier.HARD), context(hard = setOf(MuscleGroup.UPPER)))
        assertEquals(Tier.NORMAL, (c.action as BridgeAction.AddQuest).tier)
        assertEquals(NoteCode.MUSCLE_REPEAT_EASED, c.notes.single().code)
    }

    @Test
    fun `unknown goal links are dropped, known ones kept`() {
        val unknown = card(addQuest(title = "Write a section", realm = Realm.MIND, unit = null, target = null, goal = "g99"))
        assertEquals(NoteCode.UNKNOWN_GOAL, unknown.notes.single().code)
        assertEquals(null, (unknown.action as BridgeAction.AddQuest).goal)
        val known = card(addQuest(title = "Write a section", realm = Realm.MIND, unit = null, target = null, goal = "g7"))
        assertEquals(goal, known.goal)
        assertEquals(Stat.INT, known.stat)
    }

    @Test
    fun `complete, edit and remove need an open quest from today`() {
        assertEquals(CardStatus.READY, card(BridgeAction.Complete("qopen")).status)
        assertEquals(NoteCode.UNKNOWN_QUEST, card(BridgeAction.Complete("qnope")).notes.single().code)
        assertEquals(NoteCode.ALREADY_DONE, card(BridgeAction.Complete("qdone")).notes.single().code)
        assertEquals(NoteCode.NOT_CURRENT, card(BridgeAction.Remove("qold1")).notes.single().code)
        assertEquals(NoteCode.NOTHING_TO_EDIT, card(BridgeAction.Edit("qopen")).notes.single().code)
    }

    @Test
    fun `edits are clamped like new quests`() {
        val c = card(BridgeAction.Edit("qopen", target = 100.0))
        assertEquals(CardStatus.ADJUSTED, c.status)
        assertEquals(40.0, (c.action as BridgeAction.Edit).target)
    }

    @Test
    fun `quests without a template keep their stat default and absolute bounds`() {
        val c = card(addQuest(title = "Pull-ups", target = 1_000.0, unit = "reps"))
        assertEquals(300.0, (c.action as BridgeAction.AddQuest).target)
        assertEquals(Stat.STR, c.stat)
    }
}
