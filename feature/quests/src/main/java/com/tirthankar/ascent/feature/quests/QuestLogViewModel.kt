package com.tirthankar.ascent.feature.quests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.model.Arc
import com.tirthankar.ascent.data.model.Goal
import com.tirthankar.ascent.data.model.PlayerProfile
import com.tirthankar.ascent.data.model.Quest
import com.tirthankar.ascent.data.repo.ArcRepository
import com.tirthankar.ascent.data.repo.DailyCycle
import com.tirthankar.ascent.data.repo.GoalRepository
import com.tirthankar.ascent.data.repo.NewArc
import com.tirthankar.ascent.data.repo.NewGoal
import com.tirthankar.ascent.data.repo.NewQuest
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.data.repo.QuestRepository
import com.tirthankar.ascent.data.repo.SideQuestService
import com.tirthankar.ascent.data.repo.CastResult
import com.tirthankar.ascent.data.repo.PowerEffects
import com.tirthankar.ascent.data.repo.PowerRepository
import com.tirthankar.ascent.data.repo.SkillRepository
import com.tirthankar.ascent.data.db.CastTargetKind
import com.tirthankar.ascent.engine.ChapterTemplate
import com.tirthankar.ascent.engine.PowerHandlers
import com.tirthankar.ascent.engine.QuestXp
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.XpCurve
import com.tirthankar.ascent.data.repo.GuardStatus
import com.tirthankar.ascent.data.repo.HabitRepository
import com.tirthankar.ascent.engine.HabitPeriods
import com.tirthankar.ascent.engine.SeriesPeriod
import com.tirthankar.ascent.engine.StrengthSummary
import com.tirthankar.ascent.engine.Tier
import kotlin.math.roundToInt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

/** Parts of the screen state that only this ViewModel owns. */
private data class LocalState(
    val tab: QuestTab = QuestTab.TODAY,
    val reward: RewardUi? = null,
    val questForm: QuestForm? = null,
    val goalForm: GoalForm? = null,
    val bountyForm: String? = null,
    val bountyRefused: Boolean = false,
    val arcForm: ArcForm? = null,
    val cast: CastPickUi? = null,
    val showAll: Boolean = false,
    val habit: HabitDetailUi? = null,
)

/** Phase 5: cached habit strengths by series, and today's grind guard. */
private data class Metrics(
    val strengths: Map<String, StrengthSummary> = emptyMap(),
    val guard: GuardStatus = GuardStatus(),
)

/** Active casts on quests: power names per quest, and Eigenstate Collapse's target. */
private data class CastInfo(val byQuest: Map<Long, List<String>> = emptyMap(), val collapse: Long? = null)

private data class Lists(
    val today: Long,
    val day: List<Quest>,
    val week: List<Quest>,
    val goals: List<Goal>,
    val side: List<Quest>,
    val arcs: List<Arc>,
    /** Power names active on each quest today. */
    val powers: Map<Long, List<String>>,
    val collapse: Long?,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QuestLogViewModel @Inject constructor(
    private val quests: QuestRepository,
    private val goals: GoalRepository,
    private val players: PlayerRepository,
    private val gameDay: GameDay,
    private val cycle: DailyCycle,
    private val titles: TemplateTitles,
    private val arcs: ArcRepository,
    private val sideQuests: SideQuestService,
    private val time: TimeSource,
    private val powers: PowerRepository,
    private val effects: PowerEffects,
    private val skills: SkillRepository,
    private val habits: HabitRepository,
    events: GameEvents,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val today = gameDay.observeToday()

    private val minutesToReset = flow {
        while (true) {
            emit(Duration.between(gameDay.now(), gameDay.nextReset()).toMinutes().coerceAtLeast(0))
            delay(30_000)
        }
    }

    private val castsByQuest = powers.activeCasts.map { casts ->
        val onQuests = casts.filter { it.targetKind == CastTargetKind.QUEST && it.targetId != null }
        CastInfo(
            byQuest = onQuests.groupBy({ it.targetId!! }, { skills.definition(it.skillId)?.name ?: it.skillId }),
            collapse = onQuests.firstOrNull { skills.definition(it.skillId)?.handler == PowerHandlers.EIGENSTATE_COLLAPSE }?.targetId,
        )
    }

    private val lists = combine(
        combine(today.flatMapLatest { t -> quests.observeDay(t).map { t.toEpochDay() to it } }, castsByQuest) { a, b -> a to b },
        today.flatMapLatest { quests.observeWeek(it) },
        goals.activeGoals,
        today.flatMapLatest { quests.observeSide(it) },
        arcs.arcs,
    ) { (dayList, casts), week, goalList, side, arcList ->
        val (t, day) = dayList
        Lists(t, day, week, goalList, side, arcList, casts.byQuest, casts.collapse)
    }

    private val metrics = combine(habits.strengths, today.flatMapLatest { quests.observeGuard(it) }) { s, g -> Metrics(s, g) }

    val uiState: StateFlow<QuestLogUiState> =
        combine(lists, players.profile, minutesToReset, local, metrics) { l, profile, minutes, s, m ->
            buildState(l, profile, minutes, s, m)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuestLogUiState())

    init {
        // The day can roll over while the screen is open: run the reset then.
        today.onEach { cycle.ensureUpToDate() }.launchIn(viewModelScope)
        events.events.filterIsInstance<GameEvent.QuestCompleted>()
            .onEach { e ->
                local.update { it.copy(reward = RewardUi(e.questId, e.xp, e.critical, System.nanoTime())) }
            }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: QuestLogEvent) {
        when (event) {
            is QuestLogEvent.SelectTab -> local.update { it.copy(tab = event.tab) }
            is QuestLogEvent.Toggle -> toggle(event.questId)
            QuestLogEvent.RewardShown -> local.update { it.copy(reward = null) }

            QuestLogEvent.OpenQuestForm -> viewModelScope.launch {
                val scope = if (local.value.tab == QuestTab.WEEK) QuestScope.WEEKLY else QuestScope.DAILY
                local.update { it.copy(questForm = withPreview(QuestForm(scope = scope))) }
            }
            is QuestLogEvent.EditQuestForm -> viewModelScope.launch {
                val form = event.form.let { f ->
                    // Keep the stat inside the chosen realm.
                    if (f.stat.realm != f.realm) f.copy(stat = Stat.entries.first { it.realm == f.realm }) else f
                }
                local.update { it.copy(questForm = withPreview(form)) }
            }
            QuestLogEvent.SaveQuest -> saveQuest()
            QuestLogEvent.CloseQuestForm -> local.update { it.copy(questForm = null) }

            QuestLogEvent.OpenGoalForm -> local.update { it.copy(goalForm = GoalForm()) }
            is QuestLogEvent.EditGoalForm -> local.update {
                it.copy(goalForm = event.form.copy(forbidden = SafetyRules.isForbidden(event.form.title)))
            }
            QuestLogEvent.SaveGoal -> saveGoal()
            QuestLogEvent.CloseGoalForm -> local.update { it.copy(goalForm = null) }
            is QuestLogEvent.CompleteGoal -> viewModelScope.launch {
                goals.setStatus(event.goalId, GoalStatus.DONE)
                arcs.sync()
            }

            QuestLogEvent.OpenBounty -> local.update { it.copy(bountyForm = "", bountyRefused = false) }
            is QuestLogEvent.EditBounty -> local.update { it.copy(bountyForm = event.title, bountyRefused = false) }
            QuestLogEvent.SaveBounty -> viewModelScope.launch {
                val title = local.value.bountyForm?.trim().orEmpty()
                if (title.isEmpty()) return@launch
                val id = sideQuests.addBounty(title)
                local.update { if (id != null) it.copy(bountyForm = null) else it.copy(bountyRefused = true) }
            }
            QuestLogEvent.CloseBounty -> local.update { it.copy(bountyForm = null) }

            is QuestLogEvent.StartArcTemplate -> viewModelScope.launch { arcs.startFromTemplate(event.templateId) }
            is QuestLogEvent.ActivateArc -> viewModelScope.launch { arcs.activate(event.arcId) }
            is QuestLogEvent.LogObjective -> viewModelScope.launch { arcs.log(event.objectiveId, event.amount) }
            is QuestLogEvent.CompleteChapter -> viewModelScope.launch { arcs.completeChapter(event.chapterId) }
            is QuestLogEvent.LinkGoal -> viewModelScope.launch {
                goals.linkToObjective(event.goalId, event.objectiveId)
                arcs.sync()
            }
            QuestLogEvent.OpenArcForm -> local.update { it.copy(arcForm = ArcForm()) }
            is QuestLogEvent.EditArcForm -> local.update {
                val f = event.form
                it.copy(arcForm = f.copy(forbidden = SafetyRules.isForbidden(f.title) || f.chapterTitles.any(SafetyRules::isForbidden)))
            }
            QuestLogEvent.SaveArc -> saveArc()
            QuestLogEvent.CloseArcForm -> local.update { it.copy(arcForm = null) }

            is QuestLogEvent.OpenCast -> viewModelScope.launch { openCast(event.questId) }
            is QuestLogEvent.Cast -> viewModelScope.launch {
                val pick = local.value.cast ?: return@launch
                when (effects.cast(event.skillId, CastTargetKind.QUEST, pick.questId, event.input)) {
                    is CastResult.Cast -> local.update { it.copy(cast = null) }
                    // Something changed since the sheet opened: show the fresh reasons.
                    is CastResult.Refused -> openCast(pick.questId)
                }
            }
            QuestLogEvent.CloseCast -> local.update { it.copy(cast = null) }
            QuestLogEvent.ShowAll -> local.update { it.copy(showAll = true) }

            is QuestLogEvent.OpenHabit -> viewModelScope.launch { openHabit(event.questId) }
            QuestLogEvent.CloseHabit -> local.update { it.copy(habit = null) }
        }
    }

    private suspend fun openHabit(questId: Long) {
        val state = uiState.value
        val card = (state.daily + state.extra + state.weekly + state.special + state.side).firstOrNull { it.id == questId } ?: return
        val seriesId = card.seriesId ?: return
        val detail = habits.detail(seriesId)
        val s = detail.summary
        local.update {
            it.copy(
                habit = HabitDetailUi(
                    title = card.title,
                    percent = s.percent,
                    label = s.label,
                    streak = s.streak,
                    best = s.bestStreak,
                    weekly = detail.period == SeriesPeriod.WEEK,
                    measuringSince = s.sinceKey?.takeIf { s.measuring }?.let(HabitPeriods::start),
                    trajectory = detail.trajectory,
                ),
            )
        }
    }

    private suspend fun openCast(questId: Long) {
        val state = uiState.value
        val card = (state.daily + state.extra + state.weekly + state.special + state.side).firstOrNull { it.id == questId } ?: return
        if (card.done) return
        local.update { it.copy(cast = CastPickUi(questId, card.title, powers.optionsFor(CastTargetKind.QUEST, questId))) }
    }

    private fun toggle(questId: Long) = viewModelScope.launch {
        val state = uiState.value
        val card = (state.daily + state.extra + state.weekly + state.special + state.side).firstOrNull { it.id == questId } ?: return@launch
        if (card.done) {
            if (card.canUndo) quests.undo(questId)
        } else {
            quests.complete(questId)
        }
    }

    private fun saveQuest() = viewModelScope.launch {
        val form = local.value.questForm ?: return@launch
        val target = form.targetValue ?: return@launch
        if (!form.valid) return@launch
        val id = quests.add(
            NewQuest(
                title = form.title,
                realm = form.realm,
                stat = form.stat,
                tier = form.tier,
                target = target,
                unitLabel = form.unit.ifBlank { titles.unitLabel(TargetUnit.OTHER) },
                scope = form.scope,
                recurring = form.recurring,
                goalId = form.goalId,
            ),
        )
        local.update { if (id != null) it.copy(questForm = null) else it.copy(questForm = form.copy(forbidden = true)) }
    }

    private fun saveGoal() = viewModelScope.launch {
        val form = local.value.goalForm ?: return@launch
        if (!form.valid) return@launch
        val id = goals.add(NewGoal(form.horizon, form.realm, form.title, form.targetValue, form.unit.ifBlank { null }))
        local.update { if (id != null) it.copy(goalForm = null) else it.copy(goalForm = form.copy(forbidden = true)) }
    }

    private fun saveArc() = viewModelScope.launch {
        val form = local.value.arcForm ?: return@launch
        if (!form.valid) return@launch
        val id = arcs.create(
            NewArc(
                realm = form.realm,
                title = form.title,
                chapters = form.chapterTitles.map { ChapterTemplate(it) },
                finale = form.finale,
                finaleName = form.finaleName.ifBlank { null },
            ),
        )
        local.update { if (id != null) it.copy(arcForm = null) else it.copy(arcForm = form.copy(forbidden = true)) }
    }

    /**
     * The XP the quest would pay if completed now: engine math, never in the UI. Your
     * own quests are under the grind guard, so past the free slots it pays less.
     */
    private suspend fun withPreview(form: QuestForm): QuestForm {
        val profile = players.profileOnce()
        val guard = quests.guardToday().multiplierFor(form.tier)
        val xp = if (profile == null) 0 else QuestXp.xp(
            tier = form.tier,
            difficulty = profile.difficulty[form.realm] ?: 1.0,
            level = profile.level,
            streakDays = profile.streakDays,
            isCritical = false,
            bonusMultiplier = guard,
        )
        return form.copy(previewXp = xp, forbidden = SafetyRules.isForbidden(form.title), paysPercent = guard.percent())
    }

    private fun Double.percent(): Int = (this * 100).roundToInt()

    private fun buildState(l: Lists, profile: PlayerProfile?, minutes: Long, s: LocalState, m: Metrics): QuestLogUiState {
        val progress = XpCurve.levelProgress(profile?.totalXp ?: 0)
        val todayEpoch = l.today
        val started = l.arcs.mapNotNull { it.templateId }.toSet()
        fun Quest.card() = toCard(todayEpoch, l.powers, m)
        return QuestLogUiState(
            tab = s.tab,
            daily = l.day.filter { it.isDaily }.map { it.card() },
            extra = l.day.filter { !it.isDaily && it.kind == QuestKind.NORMAL }.map { it.card() },
            special = l.day.filter { it.kind == QuestKind.PENALTY || it.kind == QuestKind.EMERGENCY }.map { it.card() },
            side = l.side.map { it.card() },
            weekly = l.week.map { it.card() },
            goals = l.goals.map { GoalUi(it.id, it.publicId, it.title, it.realm, it.horizon, it.progress, it.target, it.unitLabel) },
            arcs = l.arcs.map { it.toUi(l.goals) },
            arcTemplates = arcs.templates.map { ArcTemplateUi(it.id, it.title, it.realm, it.chapters.size, it.id in started) },
            minutesToReset = minutes,
            level = profile?.level ?: 1,
            xpIntoLevel = progress.xpIntoLevel,
            xpForNext = progress.xpForNext,
            streakDays = profile?.streakDays ?: 0,
            reward = s.reward,
            questForm = s.questForm,
            goalForm = s.goalForm,
            bountyForm = s.bountyForm,
            bountyRefused = s.bountyRefused,
            arcForm = s.arcForm,
            cast = s.cast,
            // A8: the Today tab shows only the collapsed quest until it's done or the day ends.
            collapsedOn = l.collapse?.takeIf { id -> l.day.any { it.id == id && !it.isDone } || l.week.any { it.id == id && !it.isDone } },
            showAll = s.showAll,
            guard = GuardUi(m.guard.extrasToday, m.guard.fullXp, m.guard.multiplierFor(Tier.EASY).percent()),
            habit = s.habit,
        )
    }

    private fun Arc.toUi(goals: List<Goal>) = ArcUi(
        id = id,
        publicId = publicId,
        realm = realm,
        title = title,
        status = status,
        finale = finale,
        finaleName = finaleName,
        chapters = chapters.map { ch ->
            ChapterUi(
                id = ch.id,
                number = ch.position + 1,
                title = ch.title,
                status = ch.status,
                objectives = ch.objectives.map { o ->
                    ObjectiveUi(o.id, o.title, o.progress, o.target, o.unitLabel, goals.filter { it.objectiveId == o.id }.map { it.title })
                },
            )
        },
    )

    private fun Quest.toCard(today: Long, powers: Map<Long, List<String>>, m: Metrics) = QuestCardUi(
        id = id,
        publicId = publicId,
        title = title,
        realm = realm,
        stat = stat,
        tier = tier,
        target = target,
        unitLabel = unitLabel,
        done = isDone,
        canUndo = canUndo,
        recurring = recurring,
        source = source,
        xpAwarded = xpAwarded,
        kind = kind,
        minutesLeft = expiresAtMillis?.let { ((it - time.now().toEpochMilli()) / 60_000).coerceAtLeast(0) },
        daysLeft = expiresDay?.let { (it - today + 1).toInt().coerceAtLeast(0) },
        powers = powers[id].orEmpty(),
        seriesId = seriesId,
        strength = seriesId?.let { m.strengths[it] }?.percent,
        strengthLabel = seriesId?.let { m.strengths[it] }?.label,
        paysPercent = m.guard.multiplierFor(tier).takeIf { selfAdded && !isDone && it < 1.0 }?.percent(),
        paidPercent = guardMultiplier.takeIf { isDone && it < 1.0 }?.percent(),
    )
}
