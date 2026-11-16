package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads Claude's proposals (Task 34). Finds the last ```ascent block in a reply, reads
 * `v` and then each action on its own, so one bad action never hides the others.
 * Never throws: everything it can't read becomes a [ParseProblem].
 */
object AscentBlockParser {

    /**
     * v2 (Phase 3) adds side quests, arcs, Gates, bosses, boss tasks and lore; v3 (Phase 4)
     * adds `stat` and `tags` to add_quest. v1 and v2 blocks still read.
     */
    const val SUPPORTED_VERSION = 3
    private val SUPPORTED = 1..SUPPORTED_VERSION

    private val fence = Regex("""```[ \t]*ascent[^\n]*\n(.*?)(?:```|\z)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    fun parse(text: String): ParsedBlock {
        val body = findBlock(text) ?: return ParsedBlock(emptyList(), listOf(ParseProblem(ParseIssue.NO_BLOCK)))
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return ParsedBlock(emptyList(), listOf(ParseProblem(ParseIssue.BAD_JSON)))
        }
        if (root !is JsonObject) return ParsedBlock(emptyList(), listOf(ParseProblem(ParseIssue.NOT_AN_OBJECT)))

        val problems = mutableListOf<ParseProblem>()
        val version = (root["v"] as? JsonPrimitive)?.intOrNull
        if (version != null && version !in SUPPORTED) problems += ParseProblem(ParseIssue.UNSUPPORTED_VERSION)

        val actions = root["actions"] as? JsonArray
        if (actions == null) {
            problems += ParseProblem(ParseIssue.NO_ACTIONS)
            return ParsedBlock(emptyList(), problems)
        }
        val parsed = actions.mapIndexedNotNull { index, element ->
            when (val result = parseAction(element, index)) {
                is ActionResult.Ok -> IndexedAction(index, result.action)
                is ActionResult.Failed -> {
                    problems += result.problem
                    null
                }
            }
        }
        return ParsedBlock(parsed, problems)
    }

    /** The last fenced ```ascent block, or a bare JSON object that looks like one. */
    internal fun findBlock(text: String): String? {
        fence.findAll(text).lastOrNull()?.let { return it.groupValues[1].trim() }
        val start = text.lastIndexOf("{\"v\"").takeIf { it >= 0 }
            ?: text.indexOf('{').takeIf { it >= 0 && text.contains("\"actions\"") }
            ?: return null
        val end = text.lastIndexOf('}')
        return if (end > start) text.substring(start, end + 1) else null
    }

    private sealed interface ActionResult {
        data class Ok(val action: BridgeAction) : ActionResult
        data class Failed(val problem: ParseProblem) : ActionResult
    }

    private class FieldError(val problem: ParseProblem) : Exception()

    private fun parseAction(element: JsonElement, index: Int): ActionResult {
        val obj = element as? JsonObject ?: return ActionResult.Failed(ParseProblem(ParseIssue.ACTION_NOT_AN_OBJECT, index))
        val reader = Reader(obj, index)
        return try {
            val action = when (reader.string("op")?.lowercase()) {
                null -> throw FieldError(ParseProblem(ParseIssue.MISSING_FIELD, index, "op"))
                "add_goal" -> BridgeAction.AddGoal(
                    horizon = reader.enumOr("horizon", GoalHorizon.WEEK) { horizonOf(it) },
                    realm = reader.required("realm") { realmOf(it) },
                    title = reader.requiredString("title"),
                    target = reader.number("target"),
                    unit = reader.string("unit"),
                )
                "add_quest" -> {
                    val side = reader.string("scope")?.lowercase() == "side"
                    BridgeAction.AddQuest(
                        scope = if (side) QuestScope.DAILY else reader.enumOr("scope", QuestScope.DAILY) { scopeOf(it) },
                        realm = reader.required("realm") { realmOf(it) },
                        title = reader.requiredString("title"),
                        tier = reader.enumOr("tier", Tier.NORMAL) { tierOf(it) },
                        target = reader.number("target"),
                        unit = reader.string("unit"),
                        recurring = reader.boolean("recurring") ?: false,
                        goal = reader.string("goal"),
                        side = side,
                        expiresDays = reader.number("expires_days")?.toInt(),
                        stat = reader.optionalEnum("stat") { statOf(it) },
                        tags = reader.strings("tags"),
                    )
                }
                "add_arc" -> BridgeAction.AddArc(
                    realm = reader.required("realm") { realmOf(it) },
                    title = reader.requiredString("title"),
                    goal = reader.string("goal"),
                    chapters = reader.objects("chapters").map { (r, _) ->
                        BridgeAction.Chapter(r.requiredString("title"), r.objects("objectives").map { (o, _) -> o.objective() })
                    },
                    finale = reader.obj("finale")?.let { f ->
                        BridgeAction.Finale(
                            boss = f.string("boss"),
                            deadline = f.date("deadline"),
                            trial = f.string("trial"),
                            title = f.string("title"),
                        )
                    },
                )
                "add_gate" -> BridgeAction.AddGate(
                    rank = reader.enumOr("rank", Rank.E) { rankOf(it) },
                    realm = reader.required("realm") { realmOf(it) },
                    title = reader.requiredString("title"),
                    days = reader.number("days")?.toInt() ?: 7,
                    objectives = reader.objects("objectives").map { (o, _) -> o.objective() },
                )
                "add_boss" -> BridgeAction.AddBoss(
                    title = reader.requiredString("title"),
                    deadline = reader.date("deadline"),
                    arc = reader.string("arc"),
                    phases = reader.objects("phases").map { (p, _) ->
                        BridgeAction.Phase(
                            p.requiredString("name"),
                            p.objects("tasks").map { (t, _) -> BridgeAction.Task(t.requiredString("title"), t.number("hours") ?: 1.0) },
                        )
                    },
                    raid = reader.boolean("raid") ?: false,
                )
                "add_boss_task" -> BridgeAction.AddBossTask(
                    boss = reader.requiredString("boss"),
                    phase = reader.number("phase")?.toInt() ?: 1,
                    title = reader.requiredString("title"),
                    hours = reader.number("hours") ?: 1.0,
                )
                "add_lore" -> BridgeAction.AddLore(
                    target = reader.requiredString("for"),
                    event = reader.requiredString("event").lowercase(),
                    title = reader.requiredString("title"),
                    text = reader.requiredString("text"),
                )
                "complete" -> BridgeAction.Complete(reader.requiredString("id"))
                "remove" -> BridgeAction.Remove(reader.requiredString("id"))
                "edit" -> BridgeAction.Edit(
                    id = reader.requiredString("id"),
                    title = reader.string("title"),
                    tier = reader.optionalEnum("tier") { tierOf(it) },
                    target = reader.number("target"),
                    unit = reader.string("unit"),
                    recurring = reader.boolean("recurring"),
                    goal = reader.string("goal"),
                )
                else -> throw FieldError(ParseProblem(ParseIssue.UNKNOWN_OP, index, "op"))
            }
            ActionResult.Ok(action)
        } catch (e: FieldError) {
            ActionResult.Failed(e.problem)
        }
    }

    private class Reader(private val obj: JsonObject, private val index: Int) {

        private fun primitive(field: String): JsonPrimitive? {
            val value = obj[field] ?: return null
            if (value is JsonNull) return null
            return value as? JsonPrimitive ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
        }

        fun string(field: String): String? = primitive(field)?.content?.trim()?.takeIf { it.isNotEmpty() }

        fun requiredString(field: String): String =
            string(field) ?: throw FieldError(ParseProblem(ParseIssue.MISSING_FIELD, index, field))

        fun number(field: String): Double? {
            val p = primitive(field) ?: return null
            val value = p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
            if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
                throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
            }
            return value
        }

        fun boolean(field: String): Boolean? {
            val p = primitive(field) ?: return null
            return p.booleanOrNull ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
        }

        fun <T> required(field: String, map: (String) -> T?): T {
            val raw = string(field) ?: throw FieldError(ParseProblem(ParseIssue.MISSING_FIELD, index, field))
            return map(raw) ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
        }

        fun <T> enumOr(field: String, default: T, map: (String) -> T?): T {
            val raw = string(field) ?: return default
            return map(raw) ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
        }

        fun <T> optionalEnum(field: String, map: (String) -> T?): T? {
            val raw = string(field) ?: return null
            return map(raw) ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
        }

        /** An ISO date such as "2027-03-31". */
        fun date(field: String): LocalDate? {
            val raw = string(field) ?: return null
            return try {
                LocalDate.parse(raw)
            } catch (e: DateTimeParseException) {
                throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
            }
        }

        fun obj(field: String): Reader? {
            val value = obj[field] ?: return null
            if (value is JsonNull) return null
            val o = value as? JsonObject ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
            return Reader(o, index)
        }

        /** A list of objects; a missing list is empty, a bad element fails the action. */
        fun objects(field: String): List<Pair<Reader, Int>> {
            val value = obj[field] ?: return emptyList()
            if (value is JsonNull) return emptyList()
            val array = value as? JsonArray ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
            return array.mapIndexed { i, element ->
                val o = element as? JsonObject ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
                Reader(o, index) to i
            }
        }

        /** A list of strings; a missing list is empty, a non-string element fails the action. */
        fun strings(field: String): List<String> {
            val value = obj[field] ?: return emptyList()
            if (value is JsonNull) return emptyList()
            val array = value as? JsonArray ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
            return array.map { element ->
                val p = element as? JsonPrimitive ?: throw FieldError(ParseProblem(ParseIssue.BAD_VALUE, index, field))
                p.content.trim()
            }.filter { it.isNotEmpty() }
        }

        fun objective() = BridgeAction.Objective(
            title = requiredString("title"),
            target = number("target") ?: 1.0,
            unit = string("unit") ?: "done",
        )
    }

    private fun realmOf(raw: String): Realm? = Realm.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

    private fun statOf(raw: String): Stat? = Stat.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

    /** Gate ranks E–S (Σ is a player rank, never a Gate's). */
    private fun rankOf(raw: String): Rank? = Rank.entries.firstOrNull { it != Rank.SIGMA && it.name.equals(raw, ignoreCase = true) }

    /** Claude's tiers are easy, normal and hard only (SPEC). */
    private fun tierOf(raw: String): Tier? = when (raw.lowercase()) {
        "easy" -> Tier.EASY
        "normal" -> Tier.NORMAL
        "hard" -> Tier.HARD
        else -> null
    }

    private fun scopeOf(raw: String): QuestScope? = when (raw.lowercase()) {
        "daily", "day" -> QuestScope.DAILY
        "weekly", "week" -> QuestScope.WEEKLY
        else -> null
    }

    private fun horizonOf(raw: String): GoalHorizon? = when (raw.lowercase()) {
        "week", "weekly" -> GoalHorizon.WEEK
        "long", "long-term", "longterm" -> GoalHorizon.LONG
        else -> null
    }
}
