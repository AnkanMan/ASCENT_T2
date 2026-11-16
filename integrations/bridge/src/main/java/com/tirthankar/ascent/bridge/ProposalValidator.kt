package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.repo.CustomLoreEvents
import com.tirthankar.ascent.engine.ArcRules
import com.tirthankar.ascent.engine.BossRules
import com.tirthankar.ascent.engine.GateRules
import com.tirthankar.ascent.engine.KnownTags
import com.tirthankar.ascent.engine.LoreRules
import com.tirthankar.ascent.engine.SideQuestRules
import com.tirthankar.ascent.engine.TemplateLibrary
import com.tirthankar.ascent.data.model.Goal
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.engine.MuscleGroup
import com.tirthankar.ascent.engine.ProposalLimits
import com.tirthankar.ascent.engine.QuestTemplate
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.TemplateMatcher
import com.tirthankar.ascent.engine.Tier

enum class CardStatus { READY, ADJUSTED, BLOCKED }

enum class NoteCode {
    UNKNOWN_QUEST,
    UNKNOWN_GOAL,
    FORBIDDEN,
    TARGET_CLAMPED,
    SLEEP_FLOOR,
    REST_DAY_EASED,
    MUSCLE_REPEAT_EASED,
    ALREADY_DONE,
    NOT_OPEN,
    NOT_CURRENT,
    NOTHING_TO_EDIT,

    // v2 (Phase 3)
    SIDE_LIMIT,
    EXPIRY_CLAMPED,
    CHAPTER_COUNT,
    UNKNOWN_ARC,
    UNKNOWN_BOSS,
    UNKNOWN_TASK,
    TASK_LOCKED,
    BOSS_NOT_ACTIVE,
    BOSS_LIMIT,
    HOURS_CLAMPED,
    BAD_PHASE,
    GATE_LIMIT,
    RANK_CORRECTED,
    DAYS_CLAMPED,
    BODY_CAP,
    UNKNOWN_TARGET,
    BAD_EVENT,
    LORE_INVALID,
    LORE_FROZEN,
}

/** What validation needs to know about your arcs, bosses, tasks and Gates (bridge v2). */
data class ArcRef(val id: Long, val realm: Realm, val title: String)

data class BossRef(val id: Long, val title: String, val phases: Int, val active: Boolean, val isTrial: Boolean)

data class TaskRef(val id: Long, val title: String, val bossTitle: String, val done: Boolean, val unlocked: Boolean)

data class GateRef(val id: Long, val title: String)

data class CardNote(val code: NoteCode, val from: Double? = null, val to: Double? = null)

/**
 * One proposal card. [action] is what will be applied, after clamping. [quest] is the
 * existing quest a complete/edit/remove points at; [stat] is the stat an added quest pays.
 */
data class ProposalCard(
    val index: Int,
    val action: BridgeAction,
    val status: CardStatus,
    val notes: List<CardNote> = emptyList(),
    val quest: Quest? = null,
    val goal: Goal? = null,
    val stat: Stat? = null,
    /** The boss task a `complete` with a t… id points at. */
    val task: TaskRef? = null,
) {
    val applicable: Boolean get() = status != CardStatus.BLOCKED
}

/** What validation needs to know about today. */
data class BridgeContext(
    val todayEpochDay: Long,
    val weekStartEpochDay: Long,
    val isRestDay: Boolean,
    val quests: Map<String, Quest>,
    val goals: Map<String, Goal>,
    /** The engine's own target for a template today. */
    val engineTarget: (QuestTemplate) -> Double?,
    /** Muscle groups that got hard work yesterday. */
    val yesterdayHardMuscles: Set<MuscleGroup> = emptySet(),
    val arcs: Map<String, ArcRef> = emptyMap(),
    val bosses: Map<String, BossRef> = emptyMap(),
    val tasks: Map<String, TaskRef> = emptyMap(),
    val gates: Map<String, GateRef> = emptyMap(),
    val openSideQuests: Int = 0,
    val openGates: Int = 0,
    val canAddBoss: Boolean = true,
    val canAddRaid: Boolean = true,
    /** Body growth the +10% weekly cap still allows this week. */
    val bodyHeadroom: Double = SafetyRules.BODY_WEEKLY_RISE - 1.0,
    /** True when that event's lore line already unlocked (frozen). */
    val loreFrozen: (LoreTarget, Long, String) -> Boolean = { _, _, _ -> false },
)

/**
 * Turns parsed actions into cards (Task 35): flags unknown ids, blocks forbidden quests,
 * clamps targets with the engine and the safety rules, and fills defaults.
 * Claude never writes to the quest log: nothing here changes data.
 */
object ProposalValidator {

    fun validate(actions: List<IndexedAction>, context: BridgeContext): List<ProposalCard> =
        actions.map { (index, action) ->
            when (action) {
                is BridgeAction.AddGoal -> addGoal(index, action)
                is BridgeAction.AddQuest -> addQuest(index, action, context)
                is BridgeAction.Complete ->
                    if (action.id.startsWith("t")) bossTask(index, action, context) else existing(index, action, action.id, context)
                is BridgeAction.Remove -> existing(index, action, action.id, context)
                is BridgeAction.Edit -> edit(index, action, context)
                is BridgeAction.AddArc -> addArc(index, action, context)
                is BridgeAction.AddGate -> addGate(index, action, context)
                is BridgeAction.AddBoss -> addBoss(index, action, context)
                is BridgeAction.AddBossTask -> addBossTask(index, action, context)
                is BridgeAction.AddLore -> addLore(index, action, context)
            }
        }

    private fun blocked(index: Int, action: BridgeAction, code: NoteCode) =
        ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(code)))

    private fun forbidden(vararg texts: String) = texts.any { SafetyRules.isForbidden(it) }

    private fun bossTask(index: Int, action: BridgeAction.Complete, context: BridgeContext): ProposalCard {
        val task = context.tasks[action.id] ?: return blocked(index, action, NoteCode.UNKNOWN_TASK)
        val code = when {
            task.done -> NoteCode.ALREADY_DONE
            !task.unlocked -> NoteCode.TASK_LOCKED
            else -> null
        }
        return ProposalCard(index, action, if (code == null) CardStatus.READY else CardStatus.BLOCKED, listOfNotNull(code?.let(::CardNote)), task = task)
    }

    private fun addArc(index: Int, action: BridgeAction.AddArc, context: BridgeContext): ProposalCard {
        if (forbidden(action.title, *action.chapters.map { it.title }.toTypedArray(), *action.chapters.flatMap { c -> c.objectives.map { it.title } }.toTypedArray())) {
            return blocked(index, action, NoteCode.FORBIDDEN)
        }
        val notes = mutableListOf<CardNote>()
        var resolved = action
        if (ArcRules.chapterCountWarning(action.chapters.size)) notes += CardNote(NoteCode.CHAPTER_COUNT, to = action.chapters.size.toDouble())
        var goal: Goal? = null
        if (action.goal != null) {
            goal = context.goals[action.goal]
            if (goal == null) {
                notes += CardNote(NoteCode.UNKNOWN_GOAL)
                resolved = resolved.copy(goal = null)
            }
        }
        val trial = action.finale?.trial
        if (trial != null && TemplateLibrary[trial] == null) {
            notes += CardNote(NoteCode.UNKNOWN_TARGET)
            resolved = resolved.copy(finale = action.finale.copy(trial = null))
        }
        return ProposalCard(index, resolved, if (notes.isEmpty()) CardStatus.READY else CardStatus.ADJUSTED, notes, goal = goal)
    }

    private fun addGate(index: Int, action: BridgeAction.AddGate, context: BridgeContext): ProposalCard {
        if (forbidden(action.title, *action.objectives.map { it.title }.toTypedArray())) return blocked(index, action, NoteCode.FORBIDDEN)
        if (context.openGates >= GateRules.MAX_OPEN) return blocked(index, action, NoteCode.GATE_LIMIT)
        val notes = mutableListOf<CardNote>()
        val days = action.days.coerceIn(3, 7)
        if (days != action.days) notes += CardNote(NoteCode.DAYS_CLAMPED, action.days.toDouble(), days.toDouble())

        // Measure the effort against a normal week of matching quests; the closest rank wins.
        var touchesBody = action.realm == Realm.BODY
        val extras = action.objectives.mapNotNull { o ->
            val unit = TargetUnit.fromLabel(o.unit)
            val template = TemplateMatcher.match(o.title, action.realm, unit) ?: return@mapNotNull null
            val amount = template.unit == unit && unit != TargetUnit.OTHER
            if (template.realm == Realm.BODY && amount) touchesBody = true
            val frequency = GateRules.normalFrequency(template.kind)
            val normalWeek = if (amount) (context.engineTarget(template) ?: return@mapNotNull null) * frequency else frequency
            GateRules.measuredExtra(o.target, normalWeek, days)
        }
        val rank = if (extras.isEmpty()) action.rank else GateRules.correctedRank(extras.average())
        if (rank != action.rank) notes += CardNote(NoteCode.RANK_CORRECTED, action.rank.ordinal.toDouble(), rank.ordinal.toDouble())
        if (touchesBody && !GateRules.bodyFits(rank, context.bodyHeadroom)) {
            return ProposalCard(index, action.copy(rank = rank, days = days), CardStatus.BLOCKED, notes + CardNote(NoteCode.BODY_CAP))
        }
        return ProposalCard(index, action.copy(rank = rank, days = days), if (notes.isEmpty()) CardStatus.READY else CardStatus.ADJUSTED, notes)
    }

    private fun addBoss(index: Int, action: BridgeAction.AddBoss, context: BridgeContext): ProposalCard {
        if (forbidden(action.title, *action.phases.flatMap { p -> p.tasks.map { it.title } }.toTypedArray())) {
            return blocked(index, action, NoteCode.FORBIDDEN)
        }
        if (if (action.raid) !context.canAddRaid else !context.canAddBoss) return blocked(index, action, NoteCode.BOSS_LIMIT)
        val notes = mutableListOf<CardNote>()
        var phases = action.phases
        if (phases.size > BossRules.MAX_PHASES) {
            notes += CardNote(NoteCode.BAD_PHASE, phases.size.toDouble(), BossRules.MAX_PHASES.toDouble())
            phases = phases.take(BossRules.MAX_PHASES)
        }
        if (phases.any { p -> p.tasks.any { BossRules.clampHours(it.hours) != it.hours } }) {
            notes += CardNote(NoteCode.HOURS_CLAMPED)
            phases = phases.map { p -> p.copy(tasks = p.tasks.map { it.copy(hours = BossRules.clampHours(it.hours)) }) }
        }
        var arc = action.arc
        if (arc != null && context.arcs[arc] == null) {
            notes += CardNote(NoteCode.UNKNOWN_ARC)
            arc = null
        }
        val resolved = action.copy(phases = phases, arc = arc)
        return ProposalCard(index, resolved, if (notes.isEmpty()) CardStatus.READY else CardStatus.ADJUSTED, notes)
    }

    private fun addBossTask(index: Int, action: BridgeAction.AddBossTask, context: BridgeContext): ProposalCard {
        if (forbidden(action.title)) return blocked(index, action, NoteCode.FORBIDDEN)
        val boss = context.bosses[action.boss] ?: return blocked(index, action, NoteCode.UNKNOWN_BOSS)
        if (!boss.active || boss.isTrial) return blocked(index, action, NoteCode.BOSS_NOT_ACTIVE)
        // Claude's phases are 1-based; the next phase number adds a phase (up to 5).
        if (action.phase < 1 || action.phase > minOf(boss.phases + 1, BossRules.MAX_PHASES)) return blocked(index, action, NoteCode.BAD_PHASE)
        val hours = BossRules.clampHours(action.hours)
        return if (hours != action.hours) {
            ProposalCard(index, action.copy(hours = hours), CardStatus.ADJUSTED, listOf(CardNote(NoteCode.HOURS_CLAMPED, action.hours, hours)))
        } else {
            ProposalCard(index, action, CardStatus.READY)
        }
    }

    private fun addLore(index: Int, action: BridgeAction.AddLore, context: BridgeContext): ProposalCard {
        val target = when {
            action.target.startsWith("gt") -> context.gates[action.target]?.let { LoreTarget.GATE to it.id }
            action.target.startsWith("a") -> context.arcs[action.target]?.let { LoreTarget.ARC to it.id }
            action.target.startsWith("b") -> context.bosses[action.target]?.let { LoreTarget.BOSS to it.id }
            else -> null
        } ?: return blocked(index, action, NoteCode.UNKNOWN_TARGET)
        if (CustomLoreEvents.targetOf(action.event) != target.first) return blocked(index, action, NoteCode.BAD_EVENT)
        if (!LoreRules.isValid(action.text) || LoreRules.hasFranchiseWords(action.title) || forbidden(action.title)) {
            return blocked(index, action, NoteCode.LORE_INVALID)
        }
        if (context.loreFrozen(target.first, target.second, action.event)) return blocked(index, action, NoteCode.LORE_FROZEN)
        return ProposalCard(index, action, CardStatus.READY)
    }

    /** The stat a quest pays mastery to when Claude doesn't say (SPEC: the realm's first stat). */
    fun defaultStat(realm: Realm, template: QuestTemplate?): Stat =
        template?.stat ?: when (realm) {
            Realm.BODY -> Stat.STR
            Realm.MIND -> Stat.INT
            Realm.SELF -> Stat.WIS
        }

    private fun addGoal(index: Int, action: BridgeAction.AddGoal): ProposalCard =
        if (SafetyRules.isForbidden(action.title)) {
            ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(NoteCode.FORBIDDEN)))
        } else {
            ProposalCard(index, action, CardStatus.READY)
        }

    private fun addQuest(index: Int, action: BridgeAction.AddQuest, context: BridgeContext): ProposalCard {
        if (SafetyRules.isForbidden(action.title)) {
            return ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(NoteCode.FORBIDDEN)))
        }
        val notes = mutableListOf<CardNote>()
        val unit = TargetUnit.fromLabel(action.unit.orEmpty())
        val template = TemplateMatcher.match(action.title, action.realm, unit)

        var goal: Goal? = null
        var goalId = action.goal
        if (goalId != null) {
            goal = context.goals[goalId]
            if (goal == null) {
                notes += CardNote(NoteCode.UNKNOWN_GOAL)
                goalId = null
            }
        }

        val proposed = action.target ?: 1.0
        val isSleep = TemplateMatcher.isSleep(action.title)
        val reference = template?.let(context.engineTarget)
        val target = ProposalLimits.clamp(action.realm, unit, proposed, reference, isSleep)
        if (action.target != null && target != proposed) {
            notes += CardNote(if (isSleep && target > proposed) NoteCode.SLEEP_FLOOR else NoteCode.TARGET_CLAMPED, proposed, target)
        }

        var tier = action.tier
        val daily = action.scope == QuestScope.DAILY && !action.side
        if (daily && action.realm == Realm.BODY && context.isRestDay && tier != Tier.EASY) {
            tier = Tier.EASY
            notes += CardNote(NoteCode.REST_DAY_EASED)
        }
        val muscle = template?.muscle ?: TemplateMatcher.muscleGroup(action.title)
        if (daily && tier == Tier.HARD && muscle != MuscleGroup.NONE && muscle in context.yesterdayHardMuscles) {
            tier = Tier.NORMAL
            notes += CardNote(NoteCode.MUSCLE_REPEAT_EASED)
        }

        // v3: unknown tags are dropped silently; the card shows the ones kept.
        val tags = action.tags.map { it.lowercase() }.filter { it in KnownTags.ALL }.distinct()
        var resolved = action.copy(target = target, tier = tier, goal = goalId, tags = tags)
        if (action.side) {
            if (context.openSideQuests >= SideQuestRules.MAX_VISIBLE) {
                return ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(NoteCode.SIDE_LIMIT)))
            }
            val days = SideQuestRules.claudeExpiryDays(action.expiresDays)
            if (action.expiresDays != null && days != action.expiresDays) {
                notes += CardNote(NoteCode.EXPIRY_CLAMPED, action.expiresDays.toDouble(), days.toDouble())
            }
            resolved = resolved.copy(expiresDays = days, recurring = false)
        }
        return ProposalCard(
            index = index,
            action = resolved,
            status = if (notes.isEmpty()) CardStatus.READY else CardStatus.ADJUSTED,
            notes = notes,
            goal = goal,
            stat = action.stat ?: defaultStat(action.realm, template),
        )
    }

    private fun existing(index: Int, action: BridgeAction, id: String, context: BridgeContext): ProposalCard {
        val quest = context.quests[id]
            ?: return ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(NoteCode.UNKNOWN_QUEST)))
        val blocked = blockReason(quest, context)
        return if (blocked != null) {
            ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(blocked)), quest = quest)
        } else {
            ProposalCard(index, action, CardStatus.READY, quest = quest)
        }
    }

    private fun edit(index: Int, action: BridgeAction.Edit, context: BridgeContext): ProposalCard {
        val quest = context.quests[action.id]
            ?: return ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(NoteCode.UNKNOWN_QUEST)))
        blockReason(quest, context)?.let {
            return ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(it)), quest = quest)
        }
        if (action.title != null && SafetyRules.isForbidden(action.title)) {
            return ProposalCard(index, action, CardStatus.BLOCKED, listOf(CardNote(NoteCode.FORBIDDEN)), quest = quest)
        }
        val notes = mutableListOf<CardNote>()
        var resolved = action
        if (action.goal != null && context.goals[action.goal] == null) {
            notes += CardNote(NoteCode.UNKNOWN_GOAL)
            resolved = resolved.copy(goal = null)
        }
        if (action.target != null) {
            val unitLabel = action.unit ?: quest.unitLabel
            val unit = TargetUnit.fromLabel(unitLabel)
            val title = action.title ?: quest.title
            val template = TemplateMatcher.match(title, quest.realm, unit)
            val isSleep = TemplateMatcher.isSleep(title)
            val target = ProposalLimits.clamp(quest.realm, unit, action.target, template?.let(context.engineTarget), isSleep)
            if (target != action.target) {
                notes += CardNote(if (isSleep && target > action.target) NoteCode.SLEEP_FLOOR else NoteCode.TARGET_CLAMPED, action.target, target)
                resolved = resolved.copy(target = target)
            }
        }
        if (resolved.isEmpty) {
            return ProposalCard(index, action, CardStatus.BLOCKED, notes + CardNote(NoteCode.NOTHING_TO_EDIT), quest = quest)
        }
        return ProposalCard(
            index = index,
            action = resolved,
            status = if (notes.isEmpty()) CardStatus.READY else CardStatus.ADJUSTED,
            notes = notes,
            quest = quest,
        )
    }

    private fun blockReason(quest: Quest, context: BridgeContext): NoteCode? {
        if (quest.status == QuestStatus.DONE) return NoteCode.ALREADY_DONE
        if (quest.status != QuestStatus.OPEN) return NoteCode.NOT_OPEN
        val current = when (quest.scope) {
            QuestScope.DAILY -> quest.day == context.todayEpochDay
            QuestScope.WEEKLY -> quest.day == context.weekStartEpochDay
        }
        return if (current) null else NoteCode.NOT_CURRENT
    }
}
