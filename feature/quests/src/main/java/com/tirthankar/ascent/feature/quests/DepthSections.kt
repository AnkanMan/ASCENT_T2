package com.tirthankar.ascent.feature.quests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tirthankar.ascent.data.db.ArcStatus
import com.tirthankar.ascent.data.db.ChapterStatus
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.designsystem.ObjectiveProgress
import com.tirthankar.ascent.engine.FinaleKind
import com.tirthankar.ascent.engine.Realm

/** Side quests (Task 15): balance quests, bounties and Claude's, at most three open. */
@Composable
internal fun SideSection(state: QuestLogUiState, onEvent: (QuestLogEvent) -> Unit) {
    Text(stringResource(R.string.quests_side_header).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.ArcaneViolet)
    Text(stringResource(R.string.quests_side_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
    state.side.forEach { QuestCard(it, onEvent) }
    if (state.canAddBounty) AddButton(R.string.quests_add_bounty) { onEvent(QuestLogEvent.OpenBounty) }
}

/** Main quests (Task 18): active arcs, the backlog, and arc templates to start. */
@Composable
internal fun ArcsTab(state: QuestLogUiState, onEvent: (QuestLogEvent) -> Unit) {
    val active = state.arcs.filter { it.status == ArcStatus.ACTIVE }
    val backlog = state.arcs.filter { it.status == ArcStatus.BACKLOG }
    if (state.arcs.isEmpty()) {
        Text(stringResource(R.string.arcs_none), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
    }
    active.forEach { ArcPanel(it, state, onEvent) }
    if (backlog.isNotEmpty()) {
        Text(stringResource(R.string.arcs_backlog).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
        backlog.forEach { arc ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "[${stringResource(arc.realm.labelRes()).uppercase()}] ${arc.title}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AscentColors.Mist,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEvent(QuestLogEvent.ActivateArc(arc.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.arcs_activate), color = AscentColors.GlowCyan)
                }
            }
        }
    }
    val available = state.arcTemplates.filter { !it.started }
    if (available.isNotEmpty()) {
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.arcs_templates)) {
            available.forEach { t ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
                        Text(
                            "[${stringResource(t.realm.labelRes()).uppercase()}] " + pluralStringResource(R.plurals.arcs_chapters, t.chapters, t.chapters),
                            style = MaterialTheme.typography.labelSmall,
                            color = AscentColors.Mist.copy(alpha = 0.7f),
                        )
                    }
                    TextButton(onClick = { onEvent(QuestLogEvent.StartArcTemplate(t.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.arcs_start), color = AscentColors.GlowCyan)
                    }
                }
            }
        }
    }
    AddButton(R.string.arcs_add) { onEvent(QuestLogEvent.OpenArcForm) }
}

@Composable
private fun ArcPanel(arc: ArcUi, state: QuestLogUiState, onEvent: (QuestLogEvent) -> Unit) {
    val done = arc.chapters.count { it.status == ChapterStatus.DONE }
    HoloPanel(Modifier.fillMaxWidth(), header = arc.title, accent = AscentColors.LegendGold) {
        Text(
            "[${stringResource(arc.realm.labelRes()).uppercase()}] ${arc.publicId} · " +
                stringResource(R.string.arcs_progress, done, arc.chapters.size),
            style = AscentTextStyles.SystemLabel,
            color = AscentColors.SystemBlue,
        )
        arc.finaleName?.let {
            val label = when (arc.finale) {
                FinaleKind.BOSS -> stringResource(R.string.arcs_finale_boss, it)
                FinaleKind.TITLE -> stringResource(R.string.arcs_finale_title, it)
                else -> null
            }
            label?.let { text -> Text(text, style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.75f)) }
        }
        if (arc.finale == FinaleKind.TRIAL) {
            Text(stringResource(R.string.arcs_finale_trial), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.75f))
        }
        arc.chapters.forEach { chapter -> ChapterRow(chapter, state, onEvent) }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterUi, state: QuestLogUiState, onEvent: (QuestLogEvent) -> Unit) {
    val color = when (chapter.status) {
        ChapterStatus.DONE -> AscentColors.GlowCyan
        ChapterStatus.ACTIVE -> AscentColors.Mist
        ChapterStatus.LOCKED -> AscentColors.Mist.copy(alpha = 0.45f)
    }
    val mark = when (chapter.status) {
        ChapterStatus.DONE -> "◆"
        ChapterStatus.ACTIVE -> "◇"
        ChapterStatus.LOCKED -> "·"
    }
    Column(Modifier.padding(top = 10.dp)) {
        Text("$mark ${chapter.number}. ${chapter.title}", style = MaterialTheme.typography.bodyLarge, color = color)
        if (chapter.status != ChapterStatus.ACTIVE) return@Column
        chapter.objectives.forEach { o -> ObjectiveRow(o, state, onEvent) }
        if (chapter.objectives.isEmpty() || chapter.canComplete) {
            TextButton(onClick = { onEvent(QuestLogEvent.CompleteChapter(chapter.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.arcs_complete_chapter), color = AscentColors.LegendGold)
            }
        }
    }
}

@Composable
private fun ObjectiveRow(o: ObjectiveUi, state: QuestLogUiState, onEvent: (QuestLogEvent) -> Unit) {
    var linking by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, top = 6.dp)) {
        ObjectiveProgress(
            title = o.title,
            valueText = "${formatNumber(o.progress)} / ${formatNumber(o.target)} ${o.unitLabel}",
            fraction = if (o.target > 0) (o.progress / o.target).toFloat() else 0f,
        )
        if (o.linkedGoals.isNotEmpty()) {
            Text(stringResource(R.string.arcs_linked, o.linkedGoals.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = AscentColors.GlowCyan.copy(alpha = 0.8f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val minus = stringResource(R.string.arcs_log_minus, o.title)
            val plus = stringResource(R.string.arcs_log_plus, o.title)
            TextButton(onClick = { onEvent(QuestLogEvent.LogObjective(o.id, -1.0)) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = minus }) {
                Text("−1", style = AscentTextStyles.Number, color = AscentColors.Mist)
            }
            TextButton(onClick = { onEvent(QuestLogEvent.LogObjective(o.id, 1.0)) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = plus }) {
                Text("+1 ${o.unitLabel}", style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
            }
            val weekly = state.goals.filter { it.horizon == com.tirthankar.ascent.data.db.GoalHorizon.WEEK }
            if (weekly.isNotEmpty()) {
                Box {
                    TextButton(onClick = { linking = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.arcs_link_goal), color = AscentColors.GlowCyan)
                    }
                    DropdownMenu(expanded = linking, onDismissRequest = { linking = false }) {
                        weekly.forEach { goal ->
                            DropdownMenuItem(text = { Text(goal.title) }, onClick = {
                                linking = false
                                onEvent(QuestLogEvent.LinkGoal(goal.id, o.id))
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * One tap to add an errand as a side quest (Self, Easy, 3 days). A bounty is your own
 * quest, so past the free slots it warns what it would pay ([paysPercent]).
 */
@Composable
internal fun BountyDialog(title: String, refused: Boolean, paysPercent: Int, onEvent: (QuestLogEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(QuestLogEvent.CloseBounty) },
        title = { Text(stringResource(R.string.quests_bounty_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { onEvent(QuestLogEvent.EditBounty(it)) },
                    label = { Text(stringResource(R.string.quests_bounty_label)) },
                    singleLine = true,
                )
                Text(
                    stringResource(if (refused) R.string.quests_bounty_refused else R.string.quests_bounty_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refused) AscentColors.PenaltyRed else AscentColors.Mist.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                GuardWarning(paysPercent)
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(QuestLogEvent.SaveBounty) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.quests_save))
            }
        },
        dismissButton = { TextButton(onClick = { onEvent(QuestLogEvent.CloseBounty) }) { Text(stringResource(R.string.quests_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArcFormDialog(form: ArcForm, onEvent: (QuestLogEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(QuestLogEvent.CloseArcForm) },
        title = { Text(stringResource(R.string.arcs_form_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.title, { onEvent(QuestLogEvent.EditArcForm(form.copy(title = it))) }, label = { Text(stringResource(R.string.arcs_form_name)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Realm.entries.forEach { realm ->
                        FilterChip(form.realm == realm, { onEvent(QuestLogEvent.EditArcForm(form.copy(realm = realm))) }, label = { Text(stringResource(realm.labelRes())) })
                    }
                }
                OutlinedTextField(
                    form.chapters,
                    { onEvent(QuestLogEvent.EditArcForm(form.copy(chapters = it))) },
                    label = { Text(stringResource(R.string.arcs_form_chapters)) },
                    minLines = 3,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(FinaleKind.NONE to R.string.arcs_form_finale_none, FinaleKind.BOSS to R.string.arcs_form_finale_boss, FinaleKind.TITLE to R.string.arcs_form_finale_title)
                        .forEach { (kind, label) ->
                            FilterChip(form.finale == kind, { onEvent(QuestLogEvent.EditArcForm(form.copy(finale = kind))) }, label = { Text(stringResource(label)) })
                        }
                }
                if (form.finale == FinaleKind.BOSS || form.finale == FinaleKind.TITLE) {
                    OutlinedTextField(form.finaleName, { onEvent(QuestLogEvent.EditArcForm(form.copy(finaleName = it))) }, label = { Text(stringResource(R.string.arcs_form_finale_name)) }, singleLine = true)
                }
                if (form.chapterTitles.isNotEmpty() && form.chapterTitles.size !in 3..6) {
                    Text(stringResource(R.string.arcs_form_count_warning), style = MaterialTheme.typography.bodySmall, color = AscentColors.LegendGold)
                }
                if (form.forbidden) Text(stringResource(R.string.quests_forbidden), color = AscentColors.PenaltyRed, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onEvent(QuestLogEvent.SaveArc) }, enabled = form.valid) { Text(stringResource(R.string.quests_save)) } },
        dismissButton = { TextButton(onClick = { onEvent(QuestLogEvent.CloseArcForm) }) { Text(stringResource(R.string.quests_cancel)) } },
    )
}

// ---- Previews ----

private val sampleArcs = SampleQuests.state.copy(
    tab = QuestTab.ARCS,
    arcs = listOf(
        ArcUi(
            1, "a1", Realm.MIND, "The Rod Gas", ArcStatus.ACTIVE, FinaleKind.BOSS, "The Draft",
            listOf(
                ChapterUi(1, 1, "Numerical current cumulants", ChapterStatus.DONE, emptyList()),
                ChapterUi(2, 2, "BMFT prediction", ChapterStatus.ACTIVE, listOf(ObjectiveUi(5, "Derive the BMFT prediction", 0.0, 1.0, "derivations", listOf("Finish KPZ scaling figures")))),
                ChapterUi(3, 3, "μ∞ convergence checks", ChapterStatus.LOCKED, emptyList()),
            ),
        ),
        ArcUi(2, "a2", Realm.BODY, "Iron Discipline", ArcStatus.BACKLOG, FinaleKind.TRIAL, null, emptyList()),
    ),
    arcTemplates = listOf(ArcTemplateUi("quiet_hours", "Quiet Hours", Realm.SELF, 4, started = false)),
)

@Preview(name = "Quest Log — arcs", widthDp = 400, heightDp = 1100)
@Composable
private fun ArcsPreview() {
    AscentTheme { SystemBackground { QuestLogScreen(sampleArcs, onEvent = {}) } }
}

@Preview(name = "Arc form", widthDp = 400, heightDp = 700)
@Composable
private fun ArcFormPreview() {
    AscentTheme { ArcFormDialog(ArcForm(title = "Ring and Line", chapters = "Variance on the ring\nLine limit", finale = FinaleKind.BOSS, finaleName = "The write-up"), onEvent = {}) }
}
