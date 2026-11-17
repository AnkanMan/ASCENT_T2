package com.tirthankar.ascent.feature.quests

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HexagonCheck
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.designsystem.SystemBarKind
import com.tirthankar.ascent.designsystem.SystemProgressBar
import com.tirthankar.ascent.designsystem.XpFloat
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.feature.skills.CastPickerSheet
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.StrengthLabel
import com.tirthankar.ascent.engine.Tier
import java.util.Locale

@Composable
fun QuestLogRoute(
    modifier: Modifier = Modifier,
    onOpenStudy: () -> Unit = {},
    onOpenPlanner: () -> Unit = {},
    onOpenTask: (TaskSelection) -> Unit = {},
    viewModel: QuestLogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activities: ActivitiesViewModel = hiltViewModel()
    Materialize(modifier) {
        QuestLogScreen(state, { event -> if (event is QuestLogEvent.OpenTask) onOpenTask(TaskSelection(questId = event.questId)) else viewModel.onEvent(event) }, activityHub = { ActivityHub(onOpenPlanner, onOpenTask, onOpenStudy, activities) })
    }
    state.questForm?.let { QuestFormDialog(it, state.goals, viewModel::onEvent) }
    state.goalForm?.let { GoalFormDialog(it, viewModel::onEvent) }
    state.bountyForm?.let { BountyDialog(it, state.bountyRefused, state.guard.nextPercent, viewModel::onEvent) }
    state.habit?.let { HabitDetailDialog(it, viewModel::onEvent) }
    state.arcForm?.let { ArcFormDialog(it, viewModel::onEvent) }
    state.cast?.let { pick ->
        CastPickerSheet(
            targetTitle = pick.targetTitle,
            options = pick.options,
            onCast = { id, input -> viewModel.onEvent(QuestLogEvent.Cast(id, input)) },
            onDismiss = { viewModel.onEvent(QuestLogEvent.CloseCast) },
        )
    }
}

/** The Quest Log (Task 17). Stateless: renders [state] and emits events. */
@Composable
fun QuestLogScreen(
    state: QuestLogUiState,
    onEvent: (QuestLogEvent) -> Unit,
    modifier: Modifier = Modifier,
    activityHub: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        activityHub()
        DailyHeader(state, onRewardShown = { onEvent(QuestLogEvent.RewardShown) })
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = Color.Transparent, contentColor = AscentColors.GlowCyan) {
            QuestTab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { onEvent(QuestLogEvent.SelectTab(tab)) },
                    text = {
                        Text(
                            stringResource(
                                when (tab) {
                                    QuestTab.TODAY -> R.string.quests_tab_today
                                    QuestTab.WEEK -> R.string.quests_tab_week
                                    QuestTab.ARCS -> R.string.quests_tab_arcs
                                },
                            ),
                            style = AscentTextStyles.SystemLabel,
                        )
                    },
                )
            }
        }
        val collapsed = state.collapsedCard
        if (collapsed != null && state.tab != QuestTab.ARCS) {
            CollapsedView(collapsed, onEvent)
            return@Column
        }
        when (state.tab) {
            QuestTab.TODAY -> TodayTab(state, onEvent)
            QuestTab.WEEK -> WeekTab(state, onEvent)
            QuestTab.ARCS -> ArcsTab(state, onEvent)
        }
    }
}

@Composable
private fun DailyHeader(state: QuestLogUiState, onRewardShown: () -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.quests_daily_header)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.quests_progress, state.dailyDone, state.daily.size),
                style = AscentTextStyles.Number,
                color = AscentColors.Mist,
            )
            Text(
                stringResource(R.string.quests_resets_in, state.minutesToReset / 60, state.minutesToReset % 60),
                style = AscentTextStyles.Number,
                color = AscentColors.Mist.copy(alpha = 0.8f),
            )
        }
        if (state.guard.extras > 0) {
            // The grind guard's counter (PHASE5.md section 2).
            Text(
                stringResource(R.string.quests_extras_today, state.guard.extras, state.guard.fullXp),
                style = AscentTextStyles.Number,
                color = if (state.guard.fullXp < state.guard.extras) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(Modifier.padding(top = 12.dp)) {
            SystemProgressBar(stringResource(R.string.quests_xp), state.xpIntoLevel, state.xpForNext, SystemBarKind.XP)
            state.reward?.let { reward ->
                XpFloat(
                    text = stringResource(
                        if (reward.critical) R.string.quests_xp_critical else R.string.quests_xp_gained,
                        reward.xp.toInt(),
                    ),
                    key = reward.key,
                    onFinished = onRewardShown,
                    color = if (reward.critical) AscentColors.LegendGold else AscentColors.GlowCyan,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun TodayTab(state: QuestLogUiState, onEvent: (QuestLogEvent) -> Unit) {
    if (state.daily.isEmpty() && state.extra.isEmpty()) {
        Text(stringResource(R.string.quests_none_today), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
    }
    state.special.forEach { QuestCard(it, onEvent) }
    state.daily.forEach { QuestCard(it, onEvent) }
    SideSection(state, onEvent)
    if (state.extra.isNotEmpty()) {
        Text(stringResource(R.string.quests_your_quests).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
        Text(stringResource(R.string.quests_your_quests_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
        state.extra.forEach { QuestCard(it, onEvent) }
    }
    AddButton(R.string.quests_add_quest) { onEvent(QuestLogEvent.OpenQuestForm) }
}

/** Eigenstate Collapse (A8): one quest until it's done or the day ends. "Show all" still works. */
@Composable
private fun CollapsedView(card: QuestCardUi, onEvent: (QuestLogEvent) -> Unit) {
    Text(stringResource(R.string.quests_collapsed), style = AscentTextStyles.SystemLabel, color = AscentColors.LegendGold)
    QuestCard(card, onEvent)
    TextButton(onClick = { onEvent(QuestLogEvent.ShowAll) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.quests_show_all), color = AscentColors.GlowCyan)
    }
}

@Composable
private fun WeekTab(state: QuestLogUiState, onEvent: (QuestLogEvent) -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.quests_goals_header)) {
        if (state.goals.isEmpty()) {
            Text(stringResource(R.string.quests_no_goals), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
        }
        state.goals.forEach { GoalRow(it, onEvent) }
        AddButton(R.string.quests_add_goal) { onEvent(QuestLogEvent.OpenGoalForm) }
    }
    Text(stringResource(R.string.quests_weekly_header).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
    if (state.weekly.isEmpty()) {
        Text(stringResource(R.string.quests_none_week), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
    }
    state.weekly.forEach { QuestCard(it, onEvent) }
    AddButton(R.string.quests_add_quest) { onEvent(QuestLogEvent.OpenQuestForm) }
}

@Composable
internal fun QuestCard(card: QuestCardUi, onEvent: (QuestLogEvent) -> Unit) {
    val description = when {
        !card.done -> stringResource(R.string.quests_complete_description, card.title)
        card.canUndo -> stringResource(R.string.quests_undo_description, card.title)
        else -> stringResource(R.string.quests_locked_description, card.title)
    }
    val accent = when (card.kind) {
        QuestKind.PENALTY -> AscentColors.PenaltyRed
        QuestKind.EMERGENCY -> AscentColors.LegendGold
        QuestKind.SIDE -> AscentColors.ArcaneViolet
        QuestKind.NORMAL -> AscentColors.SystemBlue
    }
    HoloPanel(
        Modifier.fillMaxWidth(),
        glow = !card.done,
        accent = accent,
        header = when (card.kind) {
            QuestKind.PENALTY -> stringResource(R.string.quests_penalty_header)
            QuestKind.EMERGENCY -> stringResource(R.string.quests_emergency_header)
            else -> null
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HexagonCheck(
                checked = card.done,
                onToggle = { onEvent(if (!card.done && card.requiresResult) QuestLogEvent.OpenTask(card.id) else QuestLogEvent.Toggle(card.id)) },
                contentDescription = description,
                enabled = card.toggleEnabled,
            )
            val castLabel = stringResource(R.string.quests_cast_on)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .then(
                        if (card.done) {
                            Modifier
                        } else {
                            // Long-press casts a power on this quest (PHASE4.md section 2); TalkBack gets an action.
                            Modifier
                                .pointerInput(card.id) { detectTapGestures(onTap = { onEvent(QuestLogEvent.OpenTask(card.id)) }, onLongPress = { onEvent(QuestLogEvent.OpenCast(card.id)) }) }
                                .semantics {
                                    customActions = listOf(CustomAccessibilityAction(castLabel) { onEvent(QuestLogEvent.OpenCast(card.id)); true })
                                }
                        },
                    ),
            ) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (card.done) AscentColors.Mist.copy(alpha = 0.6f) else AscentColors.Mist,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "[${stringResource(card.realm.labelRes()).uppercase()}]",
                        style = AscentTextStyles.SystemLabel,
                        color = AscentColors.SystemBlue,
                    )
                    Text(
                        stringResource(R.string.activity_progress, formatNumber(card.actualProgress), formatNumber(card.target), card.unitLabel),
                        style = AscentTextStyles.Number,
                        color = AscentColors.Mist.copy(alpha = 0.85f),
                    )
                    Text(stringResource(card.tier.labelRes()), style = MaterialTheme.typography.labelSmall, color = AscentColors.Mist.copy(alpha = 0.7f))
                }
                val tags = listOfNotNull(
                    stringResource(R.string.quests_repeats).takeIf { card.recurring },
                    stringResource(R.string.quests_from_claude).takeIf { card.source == QuestSource.CLAUDE },
                    card.minutesLeft?.takeIf { !card.done }?.let { stringResource(R.string.quests_emergency_left, it / 60, it % 60) },
                    card.daysLeft?.takeIf { !card.done }?.let { pluralStringResource(R.plurals.quests_side_days_left, it, it) },
                )
                if (tags.isNotEmpty()) {
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = AscentColors.GlowCyan.copy(alpha = 0.8f))
                }
                if (card.kind == QuestKind.NORMAL) TextButton(onClick = { onEvent(QuestLogEvent.OpenTask(card.id)) }) { Text(stringResource(R.string.activity_open)) }
                if (card.powers.isNotEmpty()) {
                    Text("◈ " + card.powers.joinToString(" · "), style = AscentTextStyles.SystemLabel, color = AscentColors.LegendGold)
                }
                val guard = card.paysPercent?.let { stringResource(R.string.quests_guard_pays, it) }
                    ?: card.paidPercent?.let { stringResource(R.string.quests_guard_paid, it) }
                if (guard != null || card.strength != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        card.strength?.let { StrengthChip(card, it, onEvent) }
                        guard?.let { Text(it, style = AscentTextStyles.Number, color = AscentColors.LegendGold) }
                    }
                }
            }
            if (card.done && card.xpAwarded > 0) {
                Text(
                    stringResource(R.string.quests_xp_awarded, card.xpAwarded.toInt()),
                    style = AscentTextStyles.Number,
                    color = AscentColors.GlowCyan,
                )
            }
        }
    }
}

/** "↻ 72%": the series' habit strength; tapping opens the detail (PHASE5.md section 1). */
@Composable
private fun StrengthChip(card: QuestCardUi, percent: Int, onEvent: (QuestLogEvent) -> Unit) {
    val label = card.strengthLabel
    val description = stringResource(R.string.quests_strength_description, percent, label?.let { stringResource(it.labelRes()) }.orEmpty())
    TextButton(
        onClick = { onEvent(QuestLogEvent.OpenHabit(card.id)) },
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            stringResource(R.string.quests_strength_chip, percent),
            style = AscentTextStyles.Number,
            color = label?.color() ?: AscentColors.GlowCyan,
        )
    }
}

@Composable
private fun GoalRow(goal: GoalUi, onEvent: (QuestLogEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(goal.title, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "[${stringResource(goal.realm.labelRes()).uppercase()}] ${goal.publicId}",
                    style = AscentTextStyles.SystemLabel,
                    color = AscentColors.SystemBlue,
                )
                val progress = if (goal.target != null) {
                    stringResource(R.string.quests_goal_progress, formatNumber(goal.progress), formatNumber(goal.target), goal.unitLabel.orEmpty())
                } else {
                    stringResource(R.string.quests_goal_progress_count, formatNumber(goal.progress))
                }
                Text(progress.trim(), style = AscentTextStyles.Number, color = AscentColors.Mist.copy(alpha = 0.85f))
            }
        }
        val doneDescription = stringResource(R.string.quests_goal_done_description, goal.title)
        TextButton(
            onClick = { onEvent(QuestLogEvent.CompleteGoal(goal.id)) },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = doneDescription },
        ) {
            Text(stringResource(R.string.quests_goal_done), color = AscentColors.GlowCyan)
        }
    }
}

@Composable
internal fun AddButton(@StringRes label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("+ " + stringResource(label), color = AscentColors.GlowCyan)
    }
}

@StringRes
internal fun Realm.labelRes(): Int = when (this) {
    Realm.BODY -> R.string.realm_body
    Realm.MIND -> R.string.realm_mind
    Realm.SELF -> R.string.realm_self
}

@StringRes
internal fun Tier.labelRes(): Int = when (this) {
    Tier.TRIVIAL -> R.string.tier_trivial
    Tier.EASY -> R.string.tier_easy
    Tier.NORMAL -> R.string.tier_normal
    Tier.HARD -> R.string.tier_hard
    Tier.EXTREME -> R.string.tier_extreme
}

internal fun formatNumber(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)

// ---- Previews ----

internal object SampleQuests {
    private fun card(id: Long, title: String, realm: Realm, stat: Stat, target: Double, unit: String, done: Boolean = false, source: QuestSource = QuestSource.TEMPLATE, recurring: Boolean = false) =
        QuestCardUi(id, "q$id", title, realm, stat, Tier.NORMAL, target, unit, done, canUndo = done, recurring = recurring, source = source, xpAwarded = if (done) 92 else 0)

    val state = QuestLogUiState(
        daily = listOf(
            card(1, "Push-ups", Realm.BODY, Stat.STR, 40.0, "reps", done = true).copy(strength = 72, strengthLabel = StrengthLabel.HOLDING),
            card(2, "Deep work on your main project", Realm.MIND, Stat.INT, 60.0, "min"),
            card(3, "KPZ: run L=256 sweep, plot W(t)", Realm.MIND, Stat.INT, 90.0, "min", source = QuestSource.CLAUDE),
            card(4, "Lights out by 23:00", Realm.SELF, Stat.VIT, 1.0, "done"),
            card(5, "Read", Realm.SELF, Stat.WIS, 15.0, "pages"),
        ),
        extra = listOf(
            card(6, "Stretch", Realm.BODY, Stat.AGI, 10.0, "min", source = QuestSource.MANUAL, recurring = true)
                .copy(strength = 23, strengthLabel = StrengthLabel.UNSTABLE, paysPercent = 50),
            card(13, "Clear the inbox", Realm.SELF, Stat.WIS, 1.0, "done", done = true, source = QuestSource.MANUAL).copy(paidPercent = 50),
        ),
        weekly = listOf(card(7, "Three strength sessions", Realm.BODY, Stat.STR, 3.0, "sessions", source = QuestSource.MANUAL)),
        goals = listOf(GoalUi(12, "g12", "Finish KPZ scaling figures", Realm.MIND, GoalHorizon.WEEK, 1.0, 3.0, "figures")),
        special = listOf(
            card(8, "A 15-minute walk, then one line on what got in the way", Realm.BODY, Stat.AGI, 15.0, "min").copy(kind = QuestKind.PENALTY),
            card(9, "Within 2 h: 45 minutes of focused work on your nearest deadline", Realm.MIND, Stat.INT, 45.0, "min").copy(kind = QuestKind.EMERGENCY, minutesLeft = 83),
        ),
        side = listOf(
            card(10, "Mobility flow", Realm.BODY, Stat.AGI, 15.0, "min").copy(kind = QuestKind.SIDE, daysLeft = 2),
            card(11, "Submit the travel reimbursement form", Realm.SELF, Stat.WIS, 1.0, "done", source = QuestSource.MANUAL).copy(kind = QuestKind.SIDE, daysLeft = 3),
        ),
        guard = GuardUi(extras = 4, fullXp = 3, nextPercent = 25),
        minutesToReset = 312,
        level = 10,
        xpIntoLevel = 1_309,
        xpForNext = 1_995,
        streakDays = 12,
    )
}

@Preview(name = "Quest Log — today", widthDp = 400, heightDp = 1100)
@Composable
private fun QuestLogTodayPreview() {
    AscentTheme { SystemBackground { QuestLogScreen(SampleQuests.state, onEvent = {}) } }
}

@Preview(name = "Quest Log — this week", widthDp = 400, heightDp = 900)
@Composable
private fun QuestLogWeekPreview() {
    AscentTheme { SystemBackground { QuestLogScreen(SampleQuests.state.copy(tab = QuestTab.WEEK), onEvent = {}) } }
}
