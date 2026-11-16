package com.tirthankar.ascent.feature.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirthankar.ascent.bridge.ApplyResult
import com.tirthankar.ascent.bridge.BridgeAction
import com.tirthankar.ascent.bridge.CardNote
import com.tirthankar.ascent.bridge.CardStatus
import com.tirthankar.ascent.bridge.NoteCode
import com.tirthankar.ascent.bridge.ParseIssue
import com.tirthankar.ascent.bridge.ParseProblem
import com.tirthankar.ascent.bridge.ProposalCard
import com.tirthankar.ascent.bridge.ShareBridge
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import java.util.Locale

@Composable
fun ConsoleRoute(
    modifier: Modifier = Modifier,
    viewModel: ConsoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooser = stringResource(R.string.console_share_chooser)
    val clipLabel = stringResource(R.string.console_clip_label)
    LaunchedEffect(Unit) { viewModel.onEvent(ConsoleEvent.Refresh) }
    Materialize(modifier) {
        ConsoleScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onShare = { context.startActivity(ShareBridge.shareIntent(state.statusText, chooser)) },
            onCopy = {
                ShareBridge.copy(context, clipLabel, state.statusText)
                viewModel.onEvent(ConsoleEvent.Copied)
            },
            onImport = { viewModel.onEvent(ConsoleEvent.Import(ShareBridge.readClipboard(context))) },
        )
    }
}

/** The System Console (Tasks 33 and 37). Stateless; Android intents are passed in as callbacks. */
@Composable
fun ConsoleScreen(
    state: ConsoleUiState,
    onEvent: (ConsoleEvent) -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.message?.let { Message(it) { onEvent(ConsoleEvent.ClearMessage) } }
        state.result?.let { Result(it) { onEvent(ConsoleEvent.ClearMessage) } }
        state.review?.let { Review(it, state.busy, onEvent) }
        ImportPanel(state.busy, onImport)
        AskPanel(state, onEvent, onShare, onCopy)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskPanel(
    state: ConsoleUiState,
    onEvent: (ConsoleEvent) -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.console_ask_header)) {
        Text(stringResource(R.string.console_ask_prompt), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
        Text(
            stringResource(R.string.console_starters).uppercase(),
            style = AscentTextStyles.SystemLabel,
            color = AscentColors.SystemBlue,
            modifier = Modifier.padding(top = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Starter.entries.forEach { starter ->
                val label = stringResource(starter.labelRes())
                FilterChip(
                    selected = state.starter == starter,
                    onClick = {
                        val next = if (state.starter == starter) null else starter
                        onEvent(ConsoleEvent.SelectStarter(next, if (next == null) null else label))
                    },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onShare, enabled = state.statusText.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.console_share), color = AscentColors.GlowCyan)
            }
            TextButton(onClick = onCopy, enabled = state.statusText.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.console_copy), color = AscentColors.Mist)
            }
        }
        Text(
            stringResource(R.string.console_preview).uppercase(),
            style = AscentTextStyles.SystemLabel,
            color = AscentColors.SystemBlue,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            state.statusText,
            style = AscentTextStyles.Number.copy(fontSize = AscentTextStyles.Number.fontSize * 0.85f),
            color = AscentColors.Mist.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ImportPanel(busy: Boolean, onImport: () -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.console_import_header)) {
        Text(stringResource(R.string.console_import_prompt), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
        OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
            Text(stringResource(if (busy) R.string.console_reading else R.string.console_import), color = AscentColors.GlowCyan)
        }
    }
}

@Composable
private fun Review(review: ReviewUi, busy: Boolean, onEvent: (ConsoleEvent) -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.console_proposals_header)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            review.cards.forEach { ProposalCardView(it) { onEvent(ConsoleEvent.ToggleCard(it.card.index)) } }
        }
        if (review.problems.isNotEmpty()) {
            Text(
                stringResource(R.string.console_problems_header).uppercase(),
                style = AscentTextStyles.SystemLabel,
                color = AscentColors.PenaltyRed,
                modifier = Modifier.padding(top = 12.dp),
            )
            review.problems.forEach { Text(problemText(it), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onEvent(ConsoleEvent.Dismiss) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.console_dismiss), color = AscentColors.Mist)
            }
            OutlinedButton(
                onClick = { onEvent(ConsoleEvent.ApplySelected) },
                enabled = !busy && review.selectedCount > 0,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.console_apply, review.selectedCount), color = AscentColors.GlowCyan)
            }
        }
    }
}

@Composable
private fun ProposalCardView(ui: CardUi, onToggle: () -> Unit) {
    val card = ui.card
    val title = cardTitle(card)
    val accent = when (card.status) {
        CardStatus.READY -> AscentColors.SystemBlue
        CardStatus.ADJUSTED -> AscentColors.LegendGold
        CardStatus.BLOCKED -> AscentColors.PenaltyRed
    }
    val selectDescription = stringResource(R.string.card_select_description, title)
    HoloPanel(Modifier.fillMaxWidth(), accent = accent, glow = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = ui.selected, enabled = card.applicable, role = Role.Checkbox) { onToggle() }
                .semantics { contentDescription = selectDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = ui.selected && card.applicable, onCheckedChange = null, enabled = card.applicable)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
                cardDetail(card)?.let { Text(it, style = AscentTextStyles.Number, color = AscentColors.Mist.copy(alpha = 0.8f)) }
                card.notes.forEach { Text(noteText(it), style = MaterialTheme.typography.bodySmall, color = accent) }
            }
        }
    }
}

@Composable
private fun cardTitle(card: ProposalCard): String {
    val questName = card.quest?.title
    return when (val a = card.action) {
        is BridgeAction.AddGoal -> "${stringResource(R.string.card_add_goal)} · ${a.title}"
        is BridgeAction.AddQuest -> "${stringResource(if (a.side) R.string.card_add_side else R.string.card_add_quest)} · ${a.title}"
        is BridgeAction.Complete -> if (card.task != null) {
            "${stringResource(R.string.card_hit_boss)} · ${card.task?.title}"
        } else {
            "${stringResource(R.string.card_complete)} · ${questName ?: a.id}"
        }
        is BridgeAction.Edit -> "${stringResource(R.string.card_edit)} · ${questName ?: a.id}"
        is BridgeAction.Remove -> "${stringResource(R.string.card_remove)} · ${questName ?: a.id}"
        is BridgeAction.AddArc -> "${stringResource(R.string.card_add_arc)} · ${a.title}"
        is BridgeAction.AddGate -> "${stringResource(R.string.card_add_gate)} · ${a.title}"
        is BridgeAction.AddBoss -> "${stringResource(R.string.card_add_boss)} · ${a.title}"
        is BridgeAction.AddBossTask -> "${stringResource(R.string.card_add_boss_task)} · ${a.title}"
        is BridgeAction.AddLore -> "${stringResource(R.string.card_add_lore)} · ${a.title}"
    }
}

@Composable
private fun cardDetail(card: ProposalCard): String? = when (val a = card.action) {
    is BridgeAction.AddGoal -> listOfNotNull(
        "[${stringResource(a.realm.labelRes()).uppercase()}]",
        stringResource(if (a.horizon == GoalHorizon.WEEK) R.string.card_horizon_week else R.string.card_horizon_long),
        a.target?.let { "${number(it)} ${a.unit.orEmpty()}".trim() },
    ).joinToString(" · ")
    is BridgeAction.AddQuest -> listOfNotNull(
        "[${stringResource(a.realm.labelRes()).uppercase()}]",
        stringResource(if (a.scope == QuestScope.DAILY) R.string.card_scope_daily else R.string.card_scope_weekly),
        a.target?.let { "${number(it)} ${a.unit.orEmpty()}".trim() },
        stringResource(a.tier.labelRes()),
        card.stat?.name,
        // v3: the tags kept after unknown ones were dropped.
        a.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
    ).joinToString(" · ")
    is BridgeAction.Edit -> listOfNotNull(
        a.title?.let { stringResource(R.string.card_change_title, it) },
        a.target?.let { stringResource(R.string.card_change_target, "${number(it)} ${a.unit ?: card.quest?.unitLabel.orEmpty()}".trim()) },
        a.tier?.let { stringResource(R.string.card_change_tier, stringResource(it.labelRes())) },
        a.recurring?.let { stringResource(R.string.card_change_repeat, stringResource(if (it) R.string.card_yes else R.string.card_no)) },
    ).joinToString(" · ").ifEmpty { null }
    is BridgeAction.AddArc -> listOfNotNull(
        "[${stringResource(a.realm.labelRes()).uppercase()}]",
        pluralStringResource(R.plurals.card_chapters, a.chapters.size, a.chapters.size),
        a.finale?.boss?.let { stringResource(R.string.card_finale_boss, it) },
        a.finale?.title?.let { stringResource(R.string.card_finale_title, it) },
    ).joinToString(" · ")
    is BridgeAction.AddGate -> listOf(
        "[${stringResource(a.realm.labelRes()).uppercase()}]",
        stringResource(R.string.card_gate_rank, a.rank.name),
        pluralStringResource(R.plurals.card_days, a.days, a.days),
        a.objectives.joinToString(", ") { "${number(it.target)} ${it.unit}" },
    ).joinToString(" · ")
    is BridgeAction.AddBoss -> listOfNotNull(
        pluralStringResource(R.plurals.card_phases, a.phases.size, a.phases.size),
        stringResource(R.string.card_boss_hours, number(a.phases.sumOf { p -> p.tasks.sumOf { it.hours } })),
        a.deadline?.let { stringResource(R.string.card_deadline, it.toString()) },
    ).joinToString(" · ")
    is BridgeAction.AddBossTask -> stringResource(R.string.card_task_detail, a.boss, a.phase, number(a.hours))
    is BridgeAction.AddLore -> "${a.target} · ${a.event} · ${a.text}"
    is BridgeAction.Complete -> card.task?.bossTitle ?: card.quest?.let { "${number(it.target)} ${it.unitLabel}" }
    is BridgeAction.Remove -> card.quest?.let { "${number(it.target)} ${it.unitLabel}" }
}

@Composable
private fun noteText(note: CardNote): String {
    val from = note.from?.let(::number).orEmpty()
    val to = note.to?.let(::number).orEmpty()
    return when (note.code) {
        NoteCode.UNKNOWN_QUEST -> stringResource(R.string.note_unknown_quest)
        NoteCode.UNKNOWN_GOAL -> stringResource(R.string.note_unknown_goal)
        NoteCode.FORBIDDEN -> stringResource(R.string.note_forbidden)
        NoteCode.TARGET_CLAMPED -> stringResource(R.string.note_target_clamped, from, to)
        NoteCode.SLEEP_FLOOR -> stringResource(R.string.note_sleep_floor, from, to)
        NoteCode.REST_DAY_EASED -> stringResource(R.string.note_rest_day)
        NoteCode.MUSCLE_REPEAT_EASED -> stringResource(R.string.note_muscle_repeat)
        NoteCode.ALREADY_DONE -> stringResource(R.string.note_already_done)
        NoteCode.NOT_OPEN -> stringResource(R.string.note_not_open)
        NoteCode.NOT_CURRENT -> stringResource(R.string.note_not_current)
        NoteCode.NOTHING_TO_EDIT -> stringResource(R.string.note_nothing_to_edit)
        NoteCode.SIDE_LIMIT -> stringResource(R.string.note_side_limit)
        NoteCode.EXPIRY_CLAMPED -> stringResource(R.string.note_expiry_clamped, from, to)
        NoteCode.CHAPTER_COUNT -> stringResource(R.string.note_chapter_count, to)
        NoteCode.UNKNOWN_ARC -> stringResource(R.string.note_unknown_arc)
        NoteCode.UNKNOWN_BOSS -> stringResource(R.string.note_unknown_boss)
        NoteCode.UNKNOWN_TASK -> stringResource(R.string.note_unknown_task)
        NoteCode.TASK_LOCKED -> stringResource(R.string.note_task_locked)
        NoteCode.BOSS_NOT_ACTIVE -> stringResource(R.string.note_boss_not_active)
        NoteCode.BOSS_LIMIT -> stringResource(R.string.note_boss_limit)
        NoteCode.HOURS_CLAMPED -> stringResource(R.string.note_hours_clamped)
        NoteCode.BAD_PHASE -> stringResource(R.string.note_bad_phase)
        NoteCode.GATE_LIMIT -> stringResource(R.string.note_gate_limit)
        NoteCode.RANK_CORRECTED -> stringResource(
            R.string.note_rank_corrected,
            note.from?.let { com.tirthankar.ascent.engine.Rank.entries[it.toInt()].name }.orEmpty(),
            note.to?.let { com.tirthankar.ascent.engine.Rank.entries[it.toInt()].name }.orEmpty(),
        )
        NoteCode.DAYS_CLAMPED -> stringResource(R.string.note_days_clamped, from, to)
        NoteCode.BODY_CAP -> stringResource(R.string.note_body_cap)
        NoteCode.UNKNOWN_TARGET -> stringResource(R.string.note_unknown_target)
        NoteCode.BAD_EVENT -> stringResource(R.string.note_bad_event)
        NoteCode.LORE_INVALID -> stringResource(R.string.note_lore_invalid)
        NoteCode.LORE_FROZEN -> stringResource(R.string.note_lore_frozen)
    }
}

@Composable
private fun problemText(problem: ParseProblem): String {
    val n = (problem.actionIndex ?: 0) + 1
    val field = problem.field.orEmpty()
    return when (problem.issue) {
        ParseIssue.NO_BLOCK -> stringResource(R.string.problem_no_block)
        ParseIssue.BAD_JSON -> stringResource(R.string.problem_bad_json)
        ParseIssue.NOT_AN_OBJECT -> stringResource(R.string.problem_not_object)
        ParseIssue.NO_ACTIONS -> stringResource(R.string.problem_no_actions)
        ParseIssue.UNSUPPORTED_VERSION -> stringResource(R.string.problem_version)
        ParseIssue.ACTION_NOT_AN_OBJECT -> stringResource(R.string.problem_action_not_object, n)
        ParseIssue.UNKNOWN_OP -> stringResource(R.string.problem_unknown_op, n)
        ParseIssue.MISSING_FIELD -> stringResource(R.string.problem_missing_field, n, field)
        ParseIssue.BAD_VALUE -> stringResource(R.string.problem_bad_value, n, field)
    }
}

@Composable
private fun Message(message: ConsoleMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        ConsoleMessage.CLIPBOARD_EMPTY -> R.string.console_message_clipboard_empty
        ConsoleMessage.NO_BLOCK -> R.string.console_message_no_block
        ConsoleMessage.COPIED -> R.string.console_message_copied
    }
    Notice(stringResource(text), if (message == ConsoleMessage.COPIED) AscentColors.GlowCyan else AscentColors.LegendGold, onDismiss)
}

@Composable
private fun Result(result: ApplyResult, onDismiss: () -> Unit) {
    var text = stringResource(
        R.string.console_result,
        result.applied, result.goalsAdded, result.questsAdded, result.completed, result.edited, result.removed, result.depthAdded,
    )
    if (result.failed > 0) text += " " + stringResource(R.string.console_result_failed, result.failed)
    Notice(text, AscentColors.GlowCyan, onDismiss)
}

@Composable
private fun Notice(text: String, accent: androidx.compose.ui.graphics.Color, onDismiss: () -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), accent = accent, glow = false) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.console_ok), color = accent)
        }
    }
}

private fun Starter.labelRes(): Int = when (this) {
    Starter.PLAN_DAY -> R.string.starter_plan_day
    Starter.SET_WEEK -> R.string.starter_set_week
    Starter.REPORT -> R.string.starter_report
    Starter.LOW_ENERGY -> R.string.starter_low_energy
}

private fun Realm.labelRes(): Int = when (this) {
    Realm.BODY -> R.string.realm_body
    Realm.MIND -> R.string.realm_mind
    Realm.SELF -> R.string.realm_self
}

private fun Tier.labelRes(): Int = when (this) {
    Tier.EASY -> R.string.tier_easy
    Tier.NORMAL -> R.string.tier_normal
    Tier.HARD -> R.string.tier_hard
    else -> R.string.tier_other
}

private fun number(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)

// ---- Previews ----

private val sampleCards = listOf(
    ProposalCard(0, BridgeAction.AddGoal(GoalHorizon.WEEK, Realm.MIND, "Finish KPZ scaling figures", 3.0, "figures"), CardStatus.READY),
    ProposalCard(
        1,
        BridgeAction.AddQuest(QuestScope.DAILY, Realm.BODY, "Push-ups", Tier.NORMAL, 40.0, "reps", false, null),
        CardStatus.ADJUSTED,
        listOf(CardNote(NoteCode.TARGET_CLAMPED, 60.0, 40.0)),
        stat = Stat.STR,
    ),
    ProposalCard(2, BridgeAction.AddQuest(QuestScope.DAILY, Realm.SELF, "16h fast", Tier.NORMAL, null, null, false, null), CardStatus.BLOCKED, listOf(CardNote(NoteCode.FORBIDDEN))),
    ProposalCard(3, BridgeAction.Complete("q9zzz"), CardStatus.BLOCKED, listOf(CardNote(NoteCode.UNKNOWN_QUEST))),
)

private val sampleStatus = """
    ASCENT STATUS · v1
    Date: 2026-11-11 (Wed) · resets 04:00 · rest day: no
    Player: Lv 10 · Rank E · XP 1309/1995 · streak 12 (best 20) · HP 180/250 · MP 120/164
""".trimIndent()

@Preview(name = "Console — ask the System", widthDp = 400, heightDp = 900)
@Composable
private fun ConsoleAskPreview() {
    AscentTheme {
        SystemBackground {
            ConsoleScreen(ConsoleUiState(statusText = sampleStatus, starter = Starter.PLAN_DAY), {}, {}, {}, {})
        }
    }
}

@Preview(name = "Console — proposal cards", widthDp = 400, heightDp = 1400)
@Composable
private fun ConsoleCardsPreview() {
    AscentTheme {
        SystemBackground {
            ConsoleScreen(
                ConsoleUiState(
                    statusText = sampleStatus,
                    review = ReviewUi(
                        cards = sampleCards.map { CardUi(it, it.applicable) },
                        problems = listOf(ParseProblem(ParseIssue.UNKNOWN_OP, 4, "op")),
                    ),
                ),
                {}, {}, {}, {},
            )
        }
    }
}
