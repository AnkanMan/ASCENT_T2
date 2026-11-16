package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bridge v2 (Phase 3). The reply below follows the format in
 * docs/ASCENT-System-Project-Instructions.md; add real Claude replies here as they come.
 */
class BridgeV2Test {

    private val reply = """
        ◆ WEEK SET — The Rod Gas begins. One Gate, one boss.

        - Side: travel form (2 days)
        - Arc: The Rod Gas, chapters 1–2
        - Gate: The Literature Abyss (C)
        - Boss: The Draft, phase Calculations

        ```ascent
        {"v":2,"actions":[
          {"op":"add_quest","scope":"side","realm":"SELF","title":"Submit the travel reimbursement form","tier":"easy","expires_days":2},
          {"op":"add_arc","realm":"MIND","title":"The Rod Gas","goal":"g3","chapters":[
            {"title":"Numerical current cumulants","objectives":[{"title":"Cumulants to 4th order at three densities","target":3,"unit":"densities"}]},
            {"title":"BMFT prediction","objectives":[]}],
           "finale":{"boss":"The Draft","deadline":"2027-03-31"}},
          {"op":"add_gate","rank":"C","realm":"MIND","title":"The Literature Abyss","days":7,
           "objectives":[{"title":"Read a BMFT paper, write a 3-line summary","target":3,"unit":"papers"}]},
          {"op":"add_boss","title":"The Draft","deadline":"2027-03-31","arc":"a2",
           "phases":[{"name":"Calculations","tasks":[{"title":"Write up the BMFT cumulant calculation","hours":6}]}]},
          {"op":"add_boss_task","boss":"b1","phase":2,"title":"Make figure 3","hours":3},
          {"op":"complete","id":"t4"},
          {"op":"add_lore","for":"b1","event":"defeat","title":"The Draft falls","text":"Months of hours, one manuscript. The fixed point has moved, and it carries your name."},
        ]}
        ```
    """.trimIndent()

    private val context = BridgeContext(
        todayEpochDay = 20_403,
        weekStartEpochDay = 20_400,
        isRestDay = false,
        quests = emptyMap(),
        goals = emptyMap(),
        engineTarget = { if (it.key == "mind_deep_work") 60.0 else null },
        arcs = mapOf("a2" to ArcRef(2, Realm.MIND, "The Rod Gas")),
        bosses = mapOf("b1" to BossRef(1, "The Draft", phases = 1, active = true, isTrial = false)),
        tasks = mapOf("t4" to TaskRef(4, "Write up the calculation", "The Draft", done = false, unlocked = true)),
    )

    @Test
    fun `a v2 reply parses every action, trailing comma and all`() {
        val parsed = AscentBlockParser.parse(reply)
        assertEquals(emptyList(), parsed.problems)
        assertEquals(7, parsed.actions.size)
        val side = parsed.actions[0].action as BridgeAction.AddQuest
        assertTrue(side.side)
        assertEquals(2, side.expiresDays)
        val arc = parsed.actions[1].action as BridgeAction.AddArc
        assertEquals(2, arc.chapters.size)
        assertEquals("The Draft", arc.finale?.boss)
        assertEquals(LocalDate.of(2027, 3, 31), arc.finale?.deadline)
        val gate = parsed.actions[2].action as BridgeAction.AddGate
        assertEquals(Rank.C, gate.rank)
        val boss = parsed.actions[3].action as BridgeAction.AddBoss
        assertEquals(6.0, boss.phases.single().tasks.single().hours)
        assertEquals(2, (parsed.actions[4].action as BridgeAction.AddBossTask).phase)
        assertEquals("t4", (parsed.actions[5].action as BridgeAction.Complete).id)
        assertEquals("defeat", (parsed.actions[6].action as BridgeAction.AddLore).event)
    }

    @Test
    fun `v1 blocks still read, unknown versions are flagged but read`() {
        val v1 = AscentBlockParser.parse("```ascent\n{\"v\":1,\"actions\":[{\"op\":\"complete\",\"id\":\"q7f2a\"}]}\n```")
        assertEquals(emptyList(), v1.problems)
        val v9 = AscentBlockParser.parse("```ascent\n{\"v\":9,\"actions\":[{\"op\":\"complete\",\"id\":\"q7f2a\"}]}\n```")
        assertEquals(ParseIssue.UNSUPPORTED_VERSION, v9.problems.single().issue)
        assertEquals(1, v9.actions.size)
    }

    @Test
    fun `bad v2 fields are reported per action, never thrown`() {
        val parsed = AscentBlockParser.parse(
            """```ascent
            {"v":2,"actions":[
              {"op":"add_boss","title":"X","deadline":"next March","phases":[]},
              {"op":"add_gate","rank":"Z","realm":"MIND","title":"Y"},
              {"op":"add_arc","realm":"MIND","title":"Z","chapters":"lots"},
              {"op":"add_lore","for":"b1","event":"defeat","title":"T"}
            ]}
            ```""",
        )
        assertEquals(4, parsed.problems.size)
        assertTrue(parsed.actions.isEmpty())
        assertEquals(listOf("deadline", "rank", "chapters", "text"), parsed.problems.map { it.field })
    }

    @Test
    fun `cards - side quest, arc warning, boss, task, completion and lore`() {
        val cards = ProposalValidator.validate(AscentBlockParser.parse(reply).actions, context)
        assertEquals(CardStatus.READY, cards[0].status)
        // Two chapters is fewer than three: a warning, still applicable. g3 is unknown.
        assertEquals(CardStatus.ADJUSTED, cards[1].status)
        assertTrue(cards[1].notes.any { it.code == NoteCode.CHAPTER_COUNT })
        assertTrue(cards[1].notes.any { it.code == NoteCode.UNKNOWN_GOAL })
        assertEquals(CardStatus.READY, cards[3].status)
        assertEquals(CardStatus.READY, cards[4].status)
        assertEquals(4L, cards[5].task?.id)
        assertEquals(CardStatus.READY, cards[6].status)
    }

    @Test
    fun `a gate gets the rank its effort deserves`() {
        // Objectives the app can't measure keep Claude's rank.
        val cards = ProposalValidator.validate(AscentBlockParser.parse(reply).actions, context)
        assertEquals(Rank.C, (cards[2].action as BridgeAction.AddGate).rank)
        // 560 deep-work minutes in 7 days against a normal week of 7 × 60 = 420: +33%, so C, not S.
        val measured = AscentBlockParser.parse(
            """```ascent
            {"v":2,"actions":[{"op":"add_gate","rank":"S","realm":"MIND","title":"Deep Field","days":7,
              "objectives":[{"title":"Deep work on the BMFT draft","target":560,"unit":"min"}]}]}
            ```""",
        ).actions
        val card = ProposalValidator.validate(measured, context).single()
        assertEquals(Rank.C, (card.action as BridgeAction.AddGate).rank)
        assertTrue(card.notes.any { it.code == NoteCode.RANK_CORRECTED })
    }

    @Test
    fun `limits and the Body cap block cards`() {
        val actions = AscentBlockParser.parse(reply).actions
        val full = context.copy(openGates = 2, canAddBoss = false, openSideQuests = 3)
        val cards = ProposalValidator.validate(actions, full)
        assertEquals(NoteCode.SIDE_LIMIT, cards[0].notes.single().code)
        assertEquals(NoteCode.GATE_LIMIT, cards[2].notes.single().code)
        assertEquals(NoteCode.BOSS_LIMIT, cards[3].notes.single().code)

        val body = AscentBlockParser.parse(
            """```ascent
            {"v":2,"actions":[{"op":"add_gate","rank":"B","realm":"BODY","title":"Iron Week","days":7,
              "objectives":[{"title":"Push-ups","target":200,"unit":"reps"}]}]}
            ```""",
        ).actions
        val card = ProposalValidator.validate(body, context.copy(engineTarget = { if (it.key == "body_push_ups") 40.0 else null })).single()
        assertEquals(CardStatus.BLOCKED, card.status)
        assertTrue(card.notes.any { it.code == NoteCode.BODY_CAP })
    }

    @Test
    fun `boss tasks - unknown ids, locked tasks, bad phases, clamped hours`() {
        fun card(json: String, ctx: BridgeContext = context) =
            ProposalValidator.validate(AscentBlockParser.parse("```ascent\n{\"v\":2,\"actions\":[$json]}\n```").actions, ctx).single()
        assertEquals(NoteCode.UNKNOWN_TASK, card("""{"op":"complete","id":"t99"}""").notes.single().code)
        val locked = context.copy(tasks = mapOf("t4" to TaskRef(4, "x", "y", done = false, unlocked = false)))
        assertEquals(NoteCode.TASK_LOCKED, card("""{"op":"complete","id":"t4"}""", locked).notes.single().code)
        assertEquals(NoteCode.BAD_PHASE, card("""{"op":"add_boss_task","boss":"b1","phase":4,"title":"x","hours":1}""").notes.single().code)
        assertEquals(NoteCode.UNKNOWN_BOSS, card("""{"op":"add_boss_task","boss":"b9","phase":1,"title":"x","hours":1}""").notes.single().code)
        val clamped = card("""{"op":"add_boss_task","boss":"b1","phase":1,"title":"x","hours":100}""")
        assertEquals(CardStatus.ADJUSTED, clamped.status)
        assertEquals(40.0, (clamped.action as BridgeAction.AddBossTask).hours)
    }

    @Test
    fun `lore cards check the target, the event, the text and frozen lines`() {
        fun card(json: String, ctx: BridgeContext = context) =
            ProposalValidator.validate(AscentBlockParser.parse("```ascent\n{\"v\":2,\"actions\":[$json]}\n```").actions, ctx).single()
        assertEquals(NoteCode.UNKNOWN_TARGET, card("""{"op":"add_lore","for":"b7","event":"defeat","title":"t","text":"x"}""").notes.single().code)
        assertEquals(NoteCode.BAD_EVENT, card("""{"op":"add_lore","for":"b1","event":"break","title":"t","text":"x"}""").notes.single().code)
        assertEquals(NoteCode.LORE_INVALID, card("""{"op":"add_lore","for":"b1","event":"defeat","title":"t","text":"Arise, shadow."}""").notes.single().code)
        val frozen = context.copy(loreFrozen = { kind, id, event -> kind == LoreTarget.BOSS && id == 1L && event == "defeat" })
        assertEquals(NoteCode.LORE_FROZEN, card("""{"op":"add_lore","for":"b1","event":"defeat","title":"t","text":"Fine words."}""", frozen).notes.single().code)
        assertEquals(CardStatus.READY, card("""{"op":"add_lore","for":"a2","event":"chapter_done","title":"t","text":"A segment fixed."}""").status)
    }
}
