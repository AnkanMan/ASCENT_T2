package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Tier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AscentBlockParserTest {

    private val example = """
        ◆ QUEST ISSUED
        Three objectives today.

        ```ascent
        {"v":1,"actions":[
          {"op":"add_goal","horizon":"week","realm":"MIND","title":"Finish KPZ scaling figures","target":3,"unit":"figures"},
          {"op":"add_quest","scope":"daily","realm":"BODY","title":"Push-ups","tier":"normal","target":40,"unit":"reps","recurring":false,"goal":null},
          {"op":"add_quest","scope":"daily","realm":"MIND","title":"KPZ: run L=256 sweep, plot W(t)","tier":"normal","target":90,"unit":"min","goal":"g12"},
          {"op":"complete","id":"q7f2a"},
          {"op":"edit","id":"q1b9c","target":30},
          {"op":"remove","id":"q9z8y"}
        ]}
        ```
    """.trimIndent()

    @Test
    fun `reads the documented example`() {
        val parsed = AscentBlockParser.parse(example)
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(6, parsed.actions.size)
        assertEquals(
            BridgeAction.AddGoal(GoalHorizon.WEEK, Realm.MIND, "Finish KPZ scaling figures", 3.0, "figures"),
            parsed.actions[0].action,
        )
        assertEquals(
            BridgeAction.AddQuest(QuestScope.DAILY, Realm.BODY, "Push-ups", Tier.NORMAL, 40.0, "reps", false, null),
            parsed.actions[1].action,
        )
        assertEquals("g12", (parsed.actions[2].action as BridgeAction.AddQuest).goal)
        assertEquals(BridgeAction.Complete("q7f2a"), parsed.actions[3].action)
        assertEquals(BridgeAction.Edit("q1b9c", target = 30.0), parsed.actions[4].action)
        assertEquals(BridgeAction.Remove("q9z8y"), parsed.actions[5].action)
    }

    @Test
    fun `uses the last ascent block`() {
        val text = """
            ```ascent
            {"v":1,"actions":[{"op":"remove","id":"qold1"}]}
            ```
            Actually, use this:
            ```ascent
            {"v":1,"actions":[{"op":"complete","id":"qnew1"}]}
            ```
        """.trimIndent()
        assertEquals(listOf(BridgeAction.Complete("qnew1")), AscentBlockParser.parse(text).actions.map { it.action })
    }

    @Test
    fun `optional fields take their defaults`() {
        val parsed = AscentBlockParser.parse(
            """```ascent
            {"v":1,"actions":[{"op":"add_quest","realm":"self","title":"Read 15 pages","target":15,"unit":"pages"}]}
            ```""",
        )
        val quest = parsed.actions.single().action as BridgeAction.AddQuest
        assertEquals(QuestScope.DAILY, quest.scope)
        assertEquals(Tier.NORMAL, quest.tier)
        assertFalse(quest.recurring)
        assertEquals(null, quest.goal)
        assertEquals(Realm.SELF, quest.realm)
    }

    @Test
    fun `one bad action doesn't hide the others`() {
        val parsed = AscentBlockParser.parse(
            """```ascent
            {"v":1,"actions":[
              {"op":"complete","id":"q1aaa"},
              {"op":"teleport","id":"q2bbb"},
              {"op":"add_quest","realm":"BODY","tier":"normal"},
              {"op":"add_quest","realm":"SPACE","title":"x"},
              {"op":"add_quest","realm":"BODY","title":"Squats","tier":"extreme"},
              {"op":"edit","id":"q3ccc","target":"lots"},
              "not an object",
              {"op":"remove","id":"q4ddd"}
            ]}
            ```""",
        )
        assertEquals(listOf(BridgeAction.Complete("q1aaa"), BridgeAction.Remove("q4ddd")), parsed.actions.map { it.action })
        assertEquals(
            listOf(
                ParseProblem(ParseIssue.UNKNOWN_OP, 1, "op"),
                ParseProblem(ParseIssue.MISSING_FIELD, 2, "title"),
                ParseProblem(ParseIssue.BAD_VALUE, 3, "realm"),
                ParseProblem(ParseIssue.BAD_VALUE, 4, "tier"),
                ParseProblem(ParseIssue.BAD_VALUE, 5, "target"),
                ParseProblem(ParseIssue.ACTION_NOT_AN_OBJECT, 6),
            ),
            parsed.problems,
        )
    }

    @Test
    fun `tolerates trailing commas and a missing closing fence`() {
        val parsed = AscentBlockParser.parse(
            "```ascent\n{\"v\":1,\"actions\":[{\"op\":\"complete\",\"id\":\"q1aaa\"},],}\n",
        )
        assertEquals(listOf(BridgeAction.Complete("q1aaa")), parsed.actions.map { it.action })
    }

    @Test
    fun `reads a bare block pasted without fences`() {
        val parsed = AscentBlockParser.parse("""{"v":1,"actions":[{"op":"remove","id":"q4ddd"}]}""")
        assertEquals(listOf(BridgeAction.Remove("q4ddd")), parsed.actions.map { it.action })
    }

    @Test
    fun `malformed input never throws`() {
        listOf(
            "" to ParseIssue.NO_BLOCK,
            "Just a normal reply with no changes." to ParseIssue.NO_BLOCK,
            "```ascent\n{\"v\":1,\"actions\":[{\"op\":\n```" to ParseIssue.BAD_JSON,
            "```ascent\n[1,2,3]\n```" to ParseIssue.NOT_AN_OBJECT,
            "```ascent\n{\"v\":1}\n```" to ParseIssue.NO_ACTIONS,
        ).forEach { (text, issue) ->
            val parsed = AscentBlockParser.parse(text)
            assertTrue(parsed.actions.isEmpty())
            assertEquals(issue, parsed.problems.first().issue, text)
        }
        assertFalse(AscentBlockParser.parse("hello").found)
    }

    @Test
    fun `an unknown version is reported but still read`() {
        val parsed = AscentBlockParser.parse("```ascent\n{\"v\":4,\"actions\":[{\"op\":\"remove\",\"id\":\"q4ddd\"}]}\n```")
        assertEquals(ParseIssue.UNSUPPORTED_VERSION, parsed.problems.single().issue)
        assertEquals(1, parsed.actions.size)
    }

    @Test
    fun `v3 add_quest reads stat and tags, and v1 blocks still read`() {
        val parsed = AscentBlockParser.parse(
            "```ascent\n{\"v\":3,\"actions\":[{\"op\":\"add_quest\",\"realm\":\"SELF\",\"title\":\"Breathing\",\"stat\":\"vit\",\"tags\":[\"meditation\",\"Made_Up\"]}]}\n```",
        )
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        val quest = parsed.actions.single().action as BridgeAction.AddQuest
        assertEquals(com.tirthankar.ascent.engine.Stat.VIT, quest.stat)
        assertEquals(listOf("meditation", "Made_Up"), quest.tags)
        // The card keeps known tags only, silently, and pays the stat Claude named.
        val card = ProposalValidator.validate(
            parsed.actions,
            BridgeContext(0, 0, false, emptyMap(), emptyMap(), engineTarget = { null }),
        ).single()
        assertEquals(listOf("meditation"), (card.action as BridgeAction.AddQuest).tags)
        assertEquals(com.tirthankar.ascent.engine.Stat.VIT, card.stat)
        assertEquals(CardStatus.READY, card.status)

        val bad = AscentBlockParser.parse("```ascent\n{\"v\":3,\"actions\":[{\"op\":\"add_quest\",\"realm\":\"SELF\",\"title\":\"x\",\"stat\":\"LUCK\"}]}\n```")
        assertEquals(ParseIssue.BAD_VALUE, bad.problems.single().issue)
        assertEquals("stat", bad.problems.single().field)

        assertTrue(AscentBlockParser.parse(example).problems.isEmpty(), "v1 still reads")
        val old = (AscentBlockParser.parse(example).actions[1].action as BridgeAction.AddQuest)
        assertEquals(null, old.stat)
        assertTrue(old.tags.isEmpty())
    }
}
