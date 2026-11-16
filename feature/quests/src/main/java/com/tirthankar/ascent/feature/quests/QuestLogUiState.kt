package com.tirthankar.ascent.feature.quests

import com.tirthankar.ascent.data.db.ArcStatus
import com.tirthankar.ascent.data.db.ChapterStatus
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.repo.CastOption
import com.tirthankar.ascent.engine.FinaleKind
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.StrengthLabel
import com.tirthankar.ascent.engine.Tier
import java.time.LocalDate

enum class QuestTab { TODAY, WEEK, ARCS }

data class QuestCardUi(
    val id: Long,
    val publicId: String,
    val title: String,
    val realm: Realm,
    val stat: Stat,
    val tier: Tier,
    val target: Double,
    val unitLabel: String,
    val done: Boolean,
    val canUndo: Boolean,
    val recurring: Boolean,
    val source: QuestSource,
    val xpAwarded: Long,
    val kind: QuestKind = QuestKind.NORMAL,
    /** Emergency quests: minutes left in the window. */
    val minutesLeft: Long? = null,
    /** Side quests: days left including today. */
    val daysLeft: Int? = null,
    /** Powers active on this quest today (Phase 4). */
    val powers: List<String> = emptyList(),
    /** The habit series, for the detail (Phase 5). */
    val seriesId: String? = null,
    /** Habit strength of the quest's series, once it has a recorded period (Phase 5). */
    val strength: Int? = null,
    val strengthLabel: StrengthLabel? = null,
    /** Grind guard: an open self-added quest past the free slots pays this percent now. */
    val paysPercent: Int? = null,
    /** Grind guard: a done quest that was paid less than full. */
    val paidPercent: Int? = null,
) {
    /** A done quest can only be tapped again to undo it. */
    val toggleEnabled: Boolean get() = !done || canUndo
}

data class GoalUi(
    val id: Long,
    val publicId: String,
    val title: String,
    val realm: Realm,
    val horizon: GoalHorizon,
    val progress: Double,
    val target: Double?,
    val unitLabel: String?,
)

/** The XP that just landed, for the float-up animation. */
data class RewardUi(val questId: Long, val xp: Long, val critical: Boolean, val key: Long)

data class QuestForm(
    val title: String = "",
    val realm: Realm = Realm.BODY,
    val stat: Stat = Stat.STR,
    val tier: Tier = Tier.NORMAL,
    val target: String = "",
    val unit: String = "",
    val scope: QuestScope = QuestScope.DAILY,
    val recurring: Boolean = false,
    val goalId: Long? = null,
    /** XP this quest would pay right now, from the engine (after the grind guard). */
    val previewXp: Long = 0,
    val forbidden: Boolean = false,
    /** The grind guard's percent if it were completed now; below 100 shows the warning. */
    val paysPercent: Int = 100,
) {
    val targetValue: Double? get() = target.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val valid: Boolean get() = title.isNotBlank() && targetValue != null && !forbidden
}

data class GoalForm(
    val title: String = "",
    val realm: Realm = Realm.MIND,
    val horizon: GoalHorizon = GoalHorizon.WEEK,
    val target: String = "",
    val unit: String = "",
    val forbidden: Boolean = false,
) {
    val targetValue: Double? get() = target.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val valid: Boolean get() = title.isNotBlank() && !forbidden && (target.isBlank() || targetValue != null)
}

/** Today's grind guard for the header (PHASE5.md section 2). */
data class GuardUi(
    /** Self-added quests completed today. */
    val extras: Int = 0,
    /** Of those, how many paid full XP. */
    val fullXp: Int = 0,
    /** What the next Easy self-added quest (a bounty) would pay. */
    val nextPercent: Int = 100,
)

/** The quest detail's habit strength (PHASE5.md section 1). */
data class HabitDetailUi(
    val title: String,
    val percent: Int,
    val label: StrengthLabel,
    val streak: Int,
    val best: Int,
    val weekly: Boolean,
    /** Shown while fewer than 30 periods are recorded. */
    val measuringSince: LocalDate?,
    /** Strength after each of the last 90 periods, oldest first, 0..1. */
    val trajectory: List<Double>,
)

data class QuestLogUiState(
    val tab: QuestTab = QuestTab.TODAY,
    /** The Daily Quest's objectives (templates and Claude's daily quests). */
    val daily: List<QuestCardUi> = emptyList(),
    /** Daily quests you added yourself: they pay XP but don't count for the streak. */
    val extra: List<QuestCardUi> = emptyList(),
    val weekly: List<QuestCardUi> = emptyList(),
    val goals: List<GoalUi> = emptyList(),
    /** Penalty and emergency quests for today. */
    val special: List<QuestCardUi> = emptyList(),
    val side: List<QuestCardUi> = emptyList(),
    val arcs: List<ArcUi> = emptyList(),
    val arcTemplates: List<ArcTemplateUi> = emptyList(),
    val bountyForm: String? = null,
    val bountyRefused: Boolean = false,
    val arcForm: ArcForm? = null,
    val minutesToReset: Long = 0,
    val level: Int = 1,
    val xpIntoLevel: Long = 0,
    val xpForNext: Long = 100,
    val streakDays: Int = 0,
    val reward: RewardUi? = null,
    val questForm: QuestForm? = null,
    val goalForm: GoalForm? = null,
    /** The cast picker opened by a long-press (Phase 4). */
    val cast: CastPickUi? = null,
    /** Eigenstate Collapse's open target quest (A8), and whether "Show all" was tapped. */
    val collapsedOn: Long? = null,
    val showAll: Boolean = false,
    val guard: GuardUi = GuardUi(),
    /** The habit-strength detail opened from a card. */
    val habit: HabitDetailUi? = null,
) {
    /** The collapsed quest, while the view is collapsed. */
    val collapsedCard: QuestCardUi?
        get() = if (showAll) null else collapsedOn?.let { id -> (daily + extra + weekly + special + side).firstOrNull { it.id == id } }

    val dailyDone: Int get() = daily.count { it.done }
    val canAddBounty: Boolean get() = side.count { !it.done } < 3
}

data class ObjectiveUi(
    val id: Long,
    val title: String,
    val progress: Double,
    val target: Double,
    val unitLabel: String,
    /** Weekly goals feeding it. */
    val linkedGoals: List<String> = emptyList(),
) {
    val done: Boolean get() = progress >= target - 1e-9
}

data class ChapterUi(
    val id: Long,
    val number: Int,
    val title: String,
    val status: ChapterStatus,
    val objectives: List<ObjectiveUi>,
) {
    /** Completed by hand when it has no objectives (or all are done). */
    val canComplete: Boolean get() = status == ChapterStatus.ACTIVE && objectives.all { it.done }
}

data class ArcUi(
    val id: Long,
    val publicId: String,
    val realm: Realm,
    val title: String,
    val status: ArcStatus,
    val finale: FinaleKind,
    val finaleName: String?,
    val chapters: List<ChapterUi>,
)

/** Powers that can be cast on one quest, for the picker sheet. */
data class CastPickUi(val questId: Long, val targetTitle: String, val options: List<CastOption>)

data class ArcTemplateUi(val id: String, val title: String, val realm: Realm, val chapters: Int, val started: Boolean)

data class ArcForm(
    val title: String = "",
    val realm: Realm = Realm.MIND,
    /** One chapter per line. */
    val chapters: String = "",
    val finale: FinaleKind = FinaleKind.NONE,
    val finaleName: String = "",
    val forbidden: Boolean = false,
) {
    val chapterTitles: List<String> get() = chapters.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val valid: Boolean get() = title.isNotBlank() && chapterTitles.isNotEmpty() && !forbidden &&
        (finale == FinaleKind.NONE || finale == FinaleKind.TRIAL || finaleName.isNotBlank())
}

sealed interface QuestLogEvent {
    data class SelectTab(val tab: QuestTab) : QuestLogEvent
    data class Toggle(val questId: Long) : QuestLogEvent
    data object RewardShown : QuestLogEvent

    data object OpenQuestForm : QuestLogEvent
    data class EditQuestForm(val form: QuestForm) : QuestLogEvent
    data object SaveQuest : QuestLogEvent
    data object CloseQuestForm : QuestLogEvent

    data object OpenGoalForm : QuestLogEvent
    data class EditGoalForm(val form: GoalForm) : QuestLogEvent
    data object SaveGoal : QuestLogEvent
    data object CloseGoalForm : QuestLogEvent
    data class CompleteGoal(val goalId: Long) : QuestLogEvent

    // Phase 3
    data object OpenBounty : QuestLogEvent
    data class EditBounty(val title: String) : QuestLogEvent
    data object SaveBounty : QuestLogEvent
    data object CloseBounty : QuestLogEvent
    data class StartArcTemplate(val templateId: String) : QuestLogEvent
    data class ActivateArc(val arcId: Long) : QuestLogEvent
    data class LogObjective(val objectiveId: Long, val amount: Double) : QuestLogEvent
    data class CompleteChapter(val chapterId: Long) : QuestLogEvent
    data class LinkGoal(val goalId: Long, val objectiveId: Long) : QuestLogEvent
    data object OpenArcForm : QuestLogEvent
    data class EditArcForm(val form: ArcForm) : QuestLogEvent
    data object SaveArc : QuestLogEvent
    data object CloseArcForm : QuestLogEvent

    // Phase 4: casting on a quest
    data class OpenCast(val questId: Long) : QuestLogEvent
    data class Cast(val skillId: String, val input: String? = null) : QuestLogEvent
    data object CloseCast : QuestLogEvent
    data object ShowAll : QuestLogEvent

    // Phase 5: habit strength detail
    data class OpenHabit(val questId: Long) : QuestLogEvent
    data object CloseHabit : QuestLogEvent
}
