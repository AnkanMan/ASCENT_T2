package com.tirthankar.ascent.bridge

import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.db.BossStatus
import com.tirthankar.ascent.data.db.LoreTarget
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.repo.ArcRepository
import com.tirthankar.ascent.data.repo.ClassRepository
import com.tirthankar.ascent.data.repo.PowerRepository
import com.tirthankar.ascent.data.repo.PowerStanding
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.ClassRules
import com.tirthankar.ascent.engine.LimitPeriod
import com.tirthankar.ascent.data.repo.BossRepository
import com.tirthankar.ascent.data.repo.GateRepository
import com.tirthankar.ascent.data.repo.LoreRepository
import com.tirthankar.ascent.data.repo.NewArc
import com.tirthankar.ascent.data.repo.NewBoss
import com.tirthankar.ascent.data.repo.NewBossPhase
import com.tirthankar.ascent.data.repo.NewBossTask
import com.tirthankar.ascent.data.repo.NewGate
import com.tirthankar.ascent.data.repo.NewGateObjective
import com.tirthankar.ascent.engine.ChapterTemplate
import com.tirthankar.ascent.engine.FinaleKind
import com.tirthankar.ascent.engine.ObjectiveTemplate
import com.tirthankar.ascent.engine.TemplateLibrary
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.data.repo.DailyCycle
import com.tirthankar.ascent.data.repo.GoalRepository
import com.tirthankar.ascent.data.repo.HabitRepository
import com.tirthankar.ascent.data.repo.HistoryRepository
import kotlin.math.roundToInt
import com.tirthankar.ascent.data.repo.NewGoal
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.data.repo.QuestEdit
import com.tirthankar.ascent.data.repo.QuestRepository
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.MuscleGroup
import com.tirthankar.ascent.engine.QuestTemplate
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.TemplateMatcher
import com.tirthankar.ascent.engine.Tier
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A reply read and checked: cards to show, plus what couldn't be read. */
data class ProposalReview(
    val cards: List<ProposalCard>,
    val problems: List<ParseProblem>,
) {
    val foundBlock: Boolean get() = problems.none { it.issue == ParseIssue.NO_BLOCK }
}

data class ApplyResult(
    val goalsAdded: Int = 0,
    val questsAdded: Int = 0,
    val completed: Int = 0,
    val edited: Int = 0,
    val removed: Int = 0,
    val failed: Int = 0,
    /** v2: arcs, Gates, bosses, boss tasks and lore lines added. */
    val depthAdded: Int = 0,
) {
    val applied: Int get() = goalsAdded + questsAdded + completed + edited + removed + depthAdded
}

/**
 * The bridge's entry points: build the ASCENT STATUS, review a Claude reply into cards,
 * and apply the cards the player ticked, always through the repositories.
 */
@Singleton
class ProposalService @Inject constructor(
    private val players: PlayerRepository,
    private val quests: QuestRepository,
    private val goals: GoalRepository,
    private val history: HistoryRepository,
    private val cycle: DailyCycle,
    private val settings: SettingsRepository,
    private val gameDay: GameDay,
    private val titles: TemplateTitles,
    private val arcs: ArcRepository,
    private val gates: GateRepository,
    private val bosses: BossRepository,
    private val lore: LoreRepository,
    private val powers: PowerRepository,
    private val classes: ClassRepository,
    private val habits: HabitRepository,
) {

    /** The ASCENT STATUS with [request] at the end; [historyDays] is 28 for Mentor's Eye (A7). */
    suspend fun statusText(request: String?, historyDays: Int = 7): String? {
        val player = players.profile.first() ?: return null
        val today = gameDay.today()
        val s = settings.current()
        val all = quests.today()
        val todayEpoch = today.toEpochDay()
        return StatusTextBuilder.build(
            StatusSnapshot(
                today = today,
                resetTime = s.resetTime,
                isRestDay = cycle.isRestDayToday(),
                player = player,
                goals = goals.active(),
                todayQuests = all.filter { it.scope == QuestScope.DAILY && it.day == todayEpoch && it.kind != QuestKind.SIDE },
                weekQuests = all.filter { it.scope == QuestScope.WEEKLY },
                lastDays = history.lastDays(today, historyDays),
                notes = s.dossierNotes,
                request = request,
                sideQuests = all.filter { it.kind == QuestKind.SIDE },
                arcs = arcs.open(),
                gates = gates.currentList(),
                bosses = bosses.activeList(),
                historyDays = historyDays,
                classInfo = classes.definition(player.classId)?.let {
                    ClassStatus(ClassRules.displayName(it, player.rank), it.stats, it.polymath)
                },
                powers = powers.standings().map { PowerStatus(it.skill.name, it.toState()) },
                trial = gates.trial.first()?.let { t ->
                    TrialStatus(t.rank, ((t.closesDay ?: todayEpoch) - todayEpoch + 1).toInt().coerceAtLeast(0), t.objectivesDone, t.objectives.size)
                },
                // v4 (PHASE5.md section 5)
                strengths = habits.strengths.first().mapValues { it.value.percent },
                extras = quests.guardToday().let { g -> ExtrasStatus(g.extrasToday, (g.nextMultiplier * 100).roundToInt()) },
            ),
        )
    }

    /** STATUS v3's word for a power: its charges, a cooldown, used up, or ready. */
    private fun PowerStanding.toState(): PowerState {
        charges?.let { (left, total) -> return PowerState.Charges(left, total) }
        return when (val c = check) {
            is CastCheck.LimitReached -> PowerState.Used(
                when (c.period) {
                    LimitPeriod.WEEK -> "this week"
                    LimitPeriod.MONTH -> "this month"
                    else -> "today"
                },
            )
            is CastCheck.OnCooldown -> PowerState.Cooldown(((c.minutesLeft + 59) / 60).toInt())
            else -> PowerState.Ready
        }
    }

    suspend fun review(text: String): ProposalReview {
        val parsed = AscentBlockParser.parse(text)
        val today = gameDay.today()
        val ids = parsed.actions.mapNotNull { (_, action) ->
            when (action) {
                is BridgeAction.Complete -> action.id.takeUnless { it.startsWith("t") }
                is BridgeAction.Edit -> action.id
                is BridgeAction.Remove -> action.id
                else -> null
            }
        }.toSet()
        val goalIds = parsed.actions.mapNotNull { (_, action) ->
            when (action) {
                is BridgeAction.AddQuest -> action.goal
                is BridgeAction.Edit -> action.goal
                else -> null
            }
        }.toSet()
        val targets = mutableMapOf<String, Double?>()
        for ((_, action) in parsed.actions) {
            if (action is BridgeAction.AddQuest || action is BridgeAction.Edit || action is BridgeAction.AddGate) {
                templatesFor(action).forEach { t -> if (t.key !in targets) targets[t.key] = cycle.engineTarget(t) }
            }
        }
        val all = quests.today()
        val activeBosses = bosses.activeList()
        // The +10% cap's room this week, judged on today's Body targets (GateRules.bodyHeadroom).
        val headrooms: List<Double> = targets.entries.mapNotNull { (key, target) ->
            val t = TemplateLibrary[key]?.takeIf { it.realm == Realm.BODY } ?: return@mapNotNull null
            cycle.bodyHeadroom(t, target)
        }
        val bodyHeadroom = headrooms.minOrNull() ?: (com.tirthankar.ascent.engine.SafetyRules.BODY_WEEKLY_RISE - 1.0)
        val frozen = mutableMapOf<Triple<LoreTarget, Long, String>, Boolean>()
        for ((_, action) in parsed.actions) {
            if (action !is BridgeAction.AddLore) continue
            val target = when {
                action.target.startsWith("gt") -> gates.getByPublicId(action.target)?.let { LoreTarget.GATE to it.id }
                action.target.startsWith("a") -> arcs.getByPublicId(action.target)?.let { LoreTarget.ARC to it.id }
                action.target.startsWith("b") -> bosses.getByPublicId(action.target)?.let { LoreTarget.BOSS to it.id }
                else -> null
            } ?: continue
            frozen[Triple(target.first, target.second, action.event)] = lore.isCustomUnlocked(target.first, target.second, action.event)
        }
        val context = BridgeContext(
            todayEpochDay = today.toEpochDay(),
            weekStartEpochDay = GameClock.weekStart(today).toEpochDay(),
            isRestDay = cycle.isRestDayToday(),
            quests = ids.mapNotNull { id -> quests.getByPublicId(id)?.let { id to it } }.toMap(),
            goals = goalIds.mapNotNull { id -> goals.getByPublicId(id)?.let { id to it } }.toMap(),
            engineTarget = { targets[it.key] },
            yesterdayHardMuscles = hardMuscles(history.completedOn(today.minusDays(1))),
            arcs = arcs.open().associate { it.publicId to ArcRef(it.id, it.realm, it.title) },
            bosses = activeBosses.associate { it.publicId to BossRef(it.id, it.title, it.phases.size, it.status == BossStatus.ACTIVE, it.isTrial) },
            tasks = activeBosses.flatMap { b -> b.tasks.map { t -> t.publicId to TaskRef(t.id, t.title, b.title, t.done, t.unlocked) } }.toMap(),
            gates = gates.currentList().associate { it.publicId to GateRef(it.id, it.title) },
            openSideQuests = all.count { it.kind == QuestKind.SIDE && it.status == QuestStatus.OPEN },
            openGates = gates.openCount(),
            canAddBoss = bosses.canAdd(isRaid = false),
            canAddRaid = bosses.canAdd(isRaid = true),
            bodyHeadroom = bodyHeadroom,
            loreFrozen = { kind, id, event -> frozen[Triple(kind, id, event)] == true },
        )
        return ProposalReview(ProposalValidator.validate(parsed.actions, context), parsed.problems)
    }

    /** Applies the [cards] the player selected, in order. Blocked cards are skipped. */
    suspend fun apply(cards: List<ProposalCard>): ApplyResult {
        var result = ApplyResult()
        cards.filter { it.applicable }.sortedBy { it.index }.forEach { card ->
            result = when (val action = card.action) {
                is BridgeAction.AddGoal -> {
                    val id = goals.add(NewGoal(action.horizon, action.realm, action.title, action.target, action.unit, QuestSource.CLAUDE))
                    if (id != null) result.copy(goalsAdded = result.goalsAdded + 1) else result.copy(failed = result.failed + 1)
                }
                is BridgeAction.AddQuest -> {
                    val id = quests.add(
                        NewQuest(
                            title = action.title,
                            realm = action.realm,
                            stat = card.stat ?: ProposalValidator.defaultStat(action.realm, null),
                            tier = action.tier,
                            target = action.target ?: 1.0,
                            unitLabel = action.unit ?: titles.unitLabel(TargetUnit.OTHER),
                            scope = action.scope,
                            recurring = action.recurring,
                            goalId = card.goal?.id,
                            source = QuestSource.CLAUDE,
                            // Claude's daily quests are the Daily Quest (D3); side quests never are.
                            isDaily = action.scope == QuestScope.DAILY && !action.side,
                            kind = if (action.side) QuestKind.SIDE else QuestKind.NORMAL,
                            expiryDays = action.expiresDays,
                            // v3: tags let class skills and passives apply (validated against KnownTags).
                            tags = action.tags.toSet(),
                        ),
                    )
                    if (id != null) result.copy(questsAdded = result.questsAdded + 1) else result.copy(failed = result.failed + 1)
                }
                is BridgeAction.Complete -> {
                    val ok = card.task?.let { bosses.hit(it.id) } ?: card.quest?.let { quests.complete(it.id) } ?: false
                    if (ok) result.copy(completed = result.completed + 1) else result.copy(failed = result.failed + 1)
                }
                is BridgeAction.AddArc -> {
                    val finale = action.finale
                    val id = arcs.create(
                        NewArc(
                            realm = action.realm,
                            title = action.title,
                            chapters = action.chapters.map { c -> ChapterTemplate(c.title, c.objectives.map { ObjectiveTemplate(it.title, it.target, it.unit) }) },
                            finale = when {
                                finale?.boss != null -> FinaleKind.BOSS
                                finale?.trial != null -> FinaleKind.TRIAL
                                finale?.title != null -> FinaleKind.TITLE
                                else -> FinaleKind.NONE
                            },
                            finaleName = finale?.boss ?: finale?.title,
                            finaleDeadline = finale?.deadline?.toEpochDay(),
                            trialTemplate = finale?.trial,
                            goalId = card.goal?.id,
                            source = QuestSource.CLAUDE,
                        ),
                    )
                    result.depth(id != null)
                }
                is BridgeAction.AddGate -> {
                    val id = gates.addFromClaude(
                        NewGate(action.rank, action.realm, action.title, action.days, action.objectives.map { NewGateObjective(it.title, it.target, it.unit) }),
                    )
                    result.depth(id != null)
                }
                is BridgeAction.AddBoss -> {
                    val arcId = action.arc?.let { arcs.getByPublicId(it)?.id }
                    val created = bosses.create(
                        NewBoss(
                            title = action.title,
                            phases = action.phases.map { p -> NewBossPhase(p.name, p.tasks.map { NewBossTask(it.title, it.hours) }) },
                            deadlineDay = action.deadline?.toEpochDay(),
                            arcId = arcId,
                            isRaid = action.raid,
                            source = QuestSource.CLAUDE,
                        ),
                    )
                    result.depth(created.isSuccess)
                }
                is BridgeAction.AddBossTask -> {
                    val boss = bosses.getByPublicId(action.boss)
                    val ok = boss != null && bosses.addTask(boss.id, action.phase - 1, NewBossTask(action.title, action.hours))
                    result.depth(ok)
                }
                is BridgeAction.AddLore -> {
                    val target = when {
                        action.target.startsWith("gt") -> gates.getByPublicId(action.target)?.let { LoreTarget.GATE to it.id }
                        action.target.startsWith("a") -> arcs.getByPublicId(action.target)?.let { LoreTarget.ARC to it.id }
                        action.target.startsWith("b") -> bosses.getByPublicId(action.target)?.let { LoreTarget.BOSS to it.id }
                        else -> null
                    }
                    val ok = target != null && lore.addCustom(target.first, target.second, action.event, action.title, action.text)
                    result.depth(ok)
                }
                is BridgeAction.Edit -> {
                    val goalId = action.goal?.let { goals.getByPublicId(it)?.id }
                    val ok = card.quest?.let {
                        quests.edit(it.id, QuestEdit(action.title, action.tier, action.target, action.unit, action.recurring, goalId))
                    } ?: false
                    if (ok) result.copy(edited = result.edited + 1) else result.copy(failed = result.failed + 1)
                }
                is BridgeAction.Remove -> {
                    val ok = card.quest?.let { quests.remove(it.id) } ?: false
                    if (ok) result.copy(removed = result.removed + 1) else result.copy(failed = result.failed + 1)
                }
            }
        }
        return result
    }

    private fun ApplyResult.depth(ok: Boolean) = if (ok) copy(depthAdded = depthAdded + 1) else copy(failed = failed + 1)

    private suspend fun templatesFor(action: BridgeAction): List<QuestTemplate> = when (action) {
        is BridgeAction.AddQuest -> listOfNotNull(TemplateMatcher.match(action.title, action.realm, TargetUnit.fromLabel(action.unit.orEmpty())))
        is BridgeAction.AddGate -> action.objectives.mapNotNull { TemplateMatcher.match(it.title, action.realm, TargetUnit.fromLabel(it.unit)) } +
            TemplateLibrary.all.filter { it.realm == Realm.BODY && it.baseline != null }
        is BridgeAction.Edit -> {
            val quest = quests.getByPublicId(action.id)
            if (quest == null) {
                emptyList()
            } else {
                listOfNotNull(
                    TemplateMatcher.match(action.title ?: quest.title, quest.realm, TargetUnit.fromLabel(action.unit ?: quest.unitLabel)),
                )
            }
        }
        else -> emptyList()
    }

    private fun hardMuscles(done: List<Quest>): Set<MuscleGroup> =
        done.filter { it.realm == Realm.BODY && it.tier >= Tier.HARD }
            .map { TemplateMatcher.muscleGroup(it.title) }
            .filter { it != MuscleGroup.NONE }
            .toSet()

}
