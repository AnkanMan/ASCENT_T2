package com.tirthankar.ascent.feature.gates

import androidx.annotation.StringRes
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.repo.BossRefusal
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.BossHpBar
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.designsystem.ObjectiveProgress
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import java.util.Locale

@Composable
fun GatesRoute(onOpenBoss: (Long) -> Unit, modifier: Modifier = Modifier, viewModel: GatesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onEvent: (GatesEvent) -> Unit = { e -> if (e is GatesEvent.OpenBoss) onOpenBoss(e.bossId) else viewModel.onEvent(e) }
    Materialize(modifier) { GatesScreen(state, onEvent) }
    state.bossForm?.let { BossFormDialog(it, onEvent) }
}

/** The Gates tab (Tasks 21–22, 24): the board, open Gates, bosses and Trials. Stateless. */
@Composable
fun GatesScreen(state: GatesUiState, onEvent: (GatesEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.trial?.let { TrialCard(it, onEvent) }
        SectionLabel(R.string.gates_open)
        if (state.open.isEmpty()) Body(stringResource(R.string.gates_none_open))
        state.open.forEach { GateCard(it, state, onEvent) }

        SectionLabel(R.string.gates_board)
        Body(stringResource(R.string.gates_board_note))
        if (state.offers.isEmpty()) Body(stringResource(R.string.gates_board_empty))
        state.offers.forEach { GateCard(it, state, onEvent) }

        SectionLabel(R.string.gates_bosses)
        if (state.bosses.isEmpty()) Body(stringResource(R.string.gates_no_bosses))
        state.bosses.forEach { BossSummary(it, onEvent) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEvent(GatesEvent.OpenBossForm) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("+ " + stringResource(R.string.gates_add_boss), color = AscentColors.GlowCyan)
            }
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.gates_trials)) {
            Body(stringResource(R.string.gates_trials_note))
            TRIALS.forEach { (key, label) ->
                TextButton(onClick = { onEvent(GatesEvent.StartTrial(key)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gates_open_trial, stringResource(label)), color = AscentColors.GlowCyan)
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            SectionLabel(R.string.gates_recent)
            state.recent.forEach { g ->
                Text(
                    "${g.rank.label()} · ${g.title} · ${stringResource(g.status.labelRes())} · ${stringResource(R.string.gates_xp, g.xp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AscentColors.Mist.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** The rank trial (PHASE4.md section 4): 7 days, 2 objectives per realm, all six to pass. */
@Composable
private fun TrialCard(trial: GateUi, onEvent: (GatesEvent) -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.gates_rank_trial, trial.rank.label()), accent = AscentColors.LegendGold) {
        Text(
            pluralStringResource(R.plurals.gates_days_left, trial.daysLeft ?: 0, trial.daysLeft ?: 0) + " · " +
                stringResource(R.string.gates_trial_done, trial.objectives.count { it.progress >= it.target - 1e-9 }, trial.objectives.size),
            style = AscentTextStyles.Number,
            color = AscentColors.Mist,
        )
        Text(stringResource(R.string.gates_trial_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.75f), modifier = Modifier.padding(vertical = 8.dp))
        trial.objectives.forEach { o ->
            ObjectiveProgress(
                title = o.title,
                valueText = "${number(o.progress)} / ${number(o.target)} ${o.unitLabel}",
                fraction = if (o.target > 0) (o.progress / o.target).toFloat() else 0f,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (o.auto) R.string.gates_auto else R.string.gates_manual),
                    style = MaterialTheme.typography.labelSmall,
                    color = AscentColors.Mist.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                val desc = stringResource(R.string.gates_log_description, o.title)
                TextButton(onClick = { onEvent(GatesEvent.LogTrial(o.id, 1.0)) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }) {
                    Text("+1", style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
                }
            }
        }
    }
}

@Composable
private fun GateCard(gate: GateUi, state: GatesUiState, onEvent: (GatesEvent) -> Unit) {
    val accent = if (gate.isRed) AscentColors.PenaltyRed else AscentColors.SystemBlue
    HoloPanel(Modifier.fillMaxWidth(), header = gate.title, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                gate.rank.label(),
                style = AscentTextStyles.NumberLarge,
                color = accent,
                modifier = Modifier.semantics { contentDescription = "Rank ${gate.rank.name}" },
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "[${stringResource(gate.realm.labelRes()).uppercase()}] ${gate.publicId}" +
                        (if (gate.isRed) " · " + stringResource(R.string.gates_red) else ""),
                    style = AscentTextStyles.SystemLabel,
                    color = AscentColors.SystemBlue,
                )
                Text(
                    if (gate.status == GateStatus.OPEN) {
                        pluralStringResource(R.plurals.gates_days_left, gate.daysLeft ?: 0, gate.daysLeft ?: 0)
                    } else {
                        pluralStringResource(R.plurals.gates_days, gate.days, gate.days)
                    } + " · " + stringResource(R.string.gates_xp, gate.xp),
                    style = AscentTextStyles.Number,
                    color = AscentColors.Mist,
                )
            }
        }
        Text(gate.lore, style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.75f), modifier = Modifier.padding(vertical = 8.dp))
        gate.objectives.forEach { o ->
            ObjectiveProgress(
                title = o.title,
                valueText = "${number(o.progress)} / ${number(o.target)} ${o.unitLabel}",
                fraction = if (o.target > 0) (o.progress / o.target).toFloat() else 0f,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (gate.status == GateStatus.OPEN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (o.auto) Text(stringResource(R.string.gates_auto), style = MaterialTheme.typography.labelSmall, color = AscentColors.GlowCyan.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                    else Text(stringResource(R.string.gates_manual), style = MaterialTheme.typography.labelSmall, color = AscentColors.Mist.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    val desc = stringResource(R.string.gates_log_description, o.title)
                    TextButton(onClick = { onEvent(GatesEvent.Log(o.id, 1.0)) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }) {
                        Text("+1", style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
                    }
                }
            }
        }
        if (gate.status == GateStatus.OFFERED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { onEvent(GatesEvent.Accept(gate.id)) }, enabled = state.canAccept, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gates_accept), color = accent)
                }
                TextButton(onClick = { onEvent(GatesEvent.Decline(gate.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gates_decline), color = AscentColors.Mist)
                }
            }
            if (!state.canAccept) Body(stringResource(R.string.gates_limit))
        } else {
            Text(stringResource(R.string.gates_percent, gate.percent), style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
        }
    }
}

@Composable
private fun BossSummary(boss: BossSummaryUi, onEvent: (GatesEvent) -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = boss.title, accent = AscentColors.PenaltyRed) {
        if (boss.isTrial) {
            Body(stringResource(R.string.gates_trial_record, number(boss.record ?: 0.0)))
            Body(stringResource(if (boss.trialOpen) R.string.gates_trial_open else R.string.gates_trial_waiting))
        } else {
            BossHpBar(stringResource(R.string.boss_hp), boss.hp, boss.maxHp, emptyList())
            val details = listOfNotNull(
                boss.publicId,
                boss.phase?.let { stringResource(R.string.boss_phase_name, it) },
                stringResource(R.string.boss_raid).takeIf { boss.isRaid },
                boss.daysLeft?.let { if (it >= 0) pluralStringResource(R.plurals.boss_deadline_days, it.toInt(), it.toInt()) else null },
                stringResource(R.string.boss_enraged).takeIf { boss.enraged },
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (boss.enraged) AscentColors.PenaltyRed else AscentColors.Mist)
        }
        OutlinedButton(onClick = { onEvent(GatesEvent.OpenBoss(boss.id)) }, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.gates_enter_arena), color = AscentColors.PenaltyRed)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BossFormDialog(form: BossForm, onEvent: (GatesEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(GatesEvent.CloseBossForm) },
        title = { Text(stringResource(R.string.boss_form_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.title, { onEvent(GatesEvent.EditBossForm(form.copy(title = it))) }, label = { Text(stringResource(R.string.boss_form_name)) }, singleLine = true)
                OutlinedTextField(form.deadline, { onEvent(GatesEvent.EditBossForm(form.copy(deadline = it))) }, label = { Text(stringResource(R.string.boss_form_deadline)) }, singleLine = true)
                OutlinedTextField(form.firstPhase, { onEvent(GatesEvent.EditBossForm(form.copy(firstPhase = it))) }, label = { Text(stringResource(R.string.boss_form_phase)) }, singleLine = true)
                FlowRow {
                    FilterChip(form.raid, { onEvent(GatesEvent.EditBossForm(form.copy(raid = !form.raid))) }, label = { Text(stringResource(R.string.boss_form_raid)) })
                }
                Body(stringResource(R.string.boss_form_note))
                form.refusal?.let {
                    Text(
                        stringResource(if (it == BossRefusal.LIMIT) R.string.boss_form_limit else R.string.boss_form_forbidden),
                        color = AscentColors.PenaltyRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onEvent(GatesEvent.SaveBoss) }, enabled = form.valid) { Text(stringResource(R.string.boss_form_save)) } },
        dismissButton = { TextButton(onClick = { onEvent(GatesEvent.CloseBossForm) }) { Text(stringResource(R.string.boss_form_cancel)) } },
    )
}

@Composable
internal fun SectionLabel(@StringRes label: Int) {
    Text(stringResource(label).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
}

@Composable
internal fun Body(text: String, color: Color = AscentColors.Mist.copy(alpha = 0.8f)) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

internal fun Rank.label(): String = if (this == Rank.SIGMA) "Σ" else name

@StringRes
internal fun Realm.labelRes(): Int = when (this) {
    Realm.BODY -> R.string.gates_realm_body
    Realm.MIND -> R.string.gates_realm_mind
    Realm.SELF -> R.string.gates_realm_self
}

@StringRes
private fun GateStatus.labelRes(): Int = when (this) {
    GateStatus.CLEARED -> R.string.gates_status_cleared
    GateStatus.PARTIAL -> R.string.gates_status_partial
    GateStatus.BROKEN -> R.string.gates_status_broken
    GateStatus.FAILED -> R.string.gates_status_failed
    else -> R.string.gates_status_open
}

internal fun number(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)

private val TRIALS = listOf(
    "body_push_ups" to R.string.trial_push_ups,
    "body_squats" to R.string.trial_squats,
    "body_plank" to R.string.trial_plank,
)

// ---- Previews ----

internal object SampleGates {
    val open = GateUi(
        1, "gt3", "The Literature Abyss", "Every paper is a fluctuation someone else survived. Read what they left.",
        Realm.MIND, Rank.D, 7, false, GateStatus.OPEN, 4, 40, 1_200,
        listOf(
            GateObjectiveUi(1, "Read a paper and write a 3-line summary", 2.0, 3.0, "papers", true),
            GateObjectiveUi(2, "Sharpen one entry in QUESTIONS.md", 0.0, 2.0, "entries", false),
        ),
    )
    val offer = open.copy(id = 2, publicId = "gt4", title = "Silence Protocol", realm = Realm.SELF, rank = Rank.E, status = GateStatus.OFFERED, daysLeft = null, days = 5, percent = 0)
    val red = offer.copy(id = 3, publicId = "gt5", title = "Dawn Gate", rank = Rank.D, isRed = true)
    val state = GatesUiState(
        offers = listOf(offer, red),
        open = listOf(open),
        bosses = listOf(
            BossSummaryUi(1, "b1", "The Draft", 3_400, 4_000, "Calculations", false, false, null, false, 120, false),
            BossSummaryUi(2, "b2", "Trial: Push-ups", 0, 0, null, true, false, 15.0, true, null, false),
        ),
    )
}

@Preview(name = "Gates", widthDp = 400, heightDp = 1600)
@Composable
private fun GatesPreview() {
    AscentTheme { SystemBackground { GatesScreen(SampleGates.state, onEvent = {}) } }
}
