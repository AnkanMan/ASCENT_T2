package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import java.time.LocalDate

/** One action from Claude's `ascent` block (format: docs/ASCENT-System-Project-Instructions.md). */
sealed interface BridgeAction {
    data class AddGoal(
        val horizon: GoalHorizon,
        val realm: Realm,
        val title: String,
        val target: Double?,
        val unit: String?,
    ) : BridgeAction

    data class AddQuest(
        val scope: QuestScope,
        val realm: Realm,
        val title: String,
        val tier: Tier,
        val target: Double?,
        val unit: String?,
        val recurring: Boolean,
        val goal: String?,
        /** v2: scope `side` — a side quest open for [expiresDays] days. */
        val side: Boolean = false,
        val expiresDays: Int? = null,
        /** v3: the stat it pays (default: the realm's first stat) and its tags. */
        val stat: Stat? = null,
        val tags: List<String> = emptyList(),
    ) : BridgeAction

    /** Completes a quest (q…) or, from v2, a boss task (t…). */
    data class Complete(val id: String) : BridgeAction

    data class Edit(
        val id: String,
        val title: String? = null,
        val tier: Tier? = null,
        val target: Double? = null,
        val unit: String? = null,
        val recurring: Boolean? = null,
        val goal: String? = null,
    ) : BridgeAction {
        val isEmpty: Boolean
            get() = title == null && tier == null && target == null && unit == null && recurring == null && goal == null
    }

    data class Remove(val id: String) : BridgeAction

    // ---- v2 (Phase 3) ----

    data class Objective(val title: String, val target: Double, val unit: String)

    data class Chapter(val title: String, val objectives: List<Objective>)

    /** How an arc ends: a boss by name, a Trial of a template, or a title. */
    data class Finale(
        val boss: String? = null,
        val deadline: LocalDate? = null,
        val trial: String? = null,
        val title: String? = null,
    )

    data class AddArc(
        val realm: Realm,
        val title: String,
        val goal: String?,
        val chapters: List<Chapter>,
        val finale: Finale?,
    ) : BridgeAction

    data class AddGate(
        val rank: Rank,
        val realm: Realm,
        val title: String,
        val days: Int,
        val objectives: List<Objective>,
    ) : BridgeAction

    data class Task(val title: String, val hours: Double)

    data class Phase(val name: String, val tasks: List<Task>)

    data class AddBoss(
        val title: String,
        val deadline: LocalDate?,
        val arc: String?,
        val phases: List<Phase>,
        val raid: Boolean = false,
    ) : BridgeAction

    /** [phase] is 1-based, as Claude writes it. */
    data class AddBossTask(val boss: String, val phase: Int, val title: String, val hours: Double) : BridgeAction

    /** A lore line for one of your arcs (a…), bosses (b…) or Gates (gt…). */
    data class AddLore(val target: String, val event: String, val title: String, val text: String) : BridgeAction
}

enum class ParseIssue {
    /** No ```ascent block (or bare {"v":…} object) in the text. */
    NO_BLOCK,
    BAD_JSON,
    NOT_AN_OBJECT,
    NO_ACTIONS,
    UNSUPPORTED_VERSION,
    ACTION_NOT_AN_OBJECT,
    UNKNOWN_OP,
    MISSING_FIELD,
    BAD_VALUE,
}

/** Something the parser couldn't read. [actionIndex] is 0-based, null for the whole block. */
data class ParseProblem(
    val issue: ParseIssue,
    val actionIndex: Int? = null,
    val field: String? = null,
)

data class IndexedAction(val index: Int, val action: BridgeAction)

data class ParsedBlock(
    val actions: List<IndexedAction>,
    val problems: List<ParseProblem>,
) {
    val found: Boolean get() = problems.none { it.issue == ParseIssue.NO_BLOCK }
}
