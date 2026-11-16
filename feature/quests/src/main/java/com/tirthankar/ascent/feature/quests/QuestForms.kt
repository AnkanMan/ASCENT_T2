package com.tirthankar.ascent.feature.quests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier

/** Manual add (Task 19): title, realm, stat, tier, target and unit, today or this week, repeat, goal. */
@Composable
fun QuestFormDialog(form: QuestForm, goals: List<GoalUi>, onEvent: (QuestLogEvent) -> Unit) {
    Dialog(onDismissRequest = { onEvent(QuestLogEvent.CloseQuestForm) }) {
        QuestFormPanel(form, goals) { onEvent(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestFormPanel(form: QuestForm, goals: List<GoalUi>, onEvent: (QuestLogEvent) -> Unit) {
    fun edit(change: QuestForm) = onEvent(QuestLogEvent.EditQuestForm(change))
    HoloPanel(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        header = stringResource(R.string.quests_form_header),
    ) {
        OutlinedTextField(
            value = form.title,
            onValueChange = { edit(form.copy(title = it.take(80))) },
            label = { Text(stringResource(R.string.quests_form_title)) },
            singleLine = true,
            isError = form.forbidden,
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.forbidden) {
            Text(stringResource(R.string.quests_form_forbidden), style = MaterialTheme.typography.bodySmall, color = AscentColors.PenaltyRed)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = form.target,
                onValueChange = { edit(form.copy(target = it.take(7))) },
                label = { Text(stringResource(R.string.quests_form_target)) },
                singleLine = true,
                textStyle = AscentTextStyles.Number,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.unit,
                onValueChange = { edit(form.copy(unit = it.take(16))) },
                label = { Text(stringResource(R.string.quests_form_unit)) },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
        }

        Label(R.string.quests_form_realm)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Realm.entries.forEach { realm ->
                Chip(form.realm == realm, stringResource(realm.labelRes())) { edit(form.copy(realm = realm)) }
            }
        }
        Label(R.string.quests_form_stat)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat.entries.filter { it.realm == form.realm }.forEach { stat ->
                Chip(form.stat == stat, stat.name) { edit(form.copy(stat = stat)) }
            }
        }
        Label(R.string.quests_form_tier)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tier.entries.forEach { tier ->
                Chip(form.tier == tier, stringResource(tier.labelRes())) { edit(form.copy(tier = tier)) }
            }
        }
        Label(R.string.quests_form_scope)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(form.scope == QuestScope.DAILY, stringResource(R.string.quests_form_daily)) { edit(form.copy(scope = QuestScope.DAILY)) }
            Chip(form.scope == QuestScope.WEEKLY, stringResource(R.string.quests_form_weekly)) { edit(form.copy(scope = QuestScope.WEEKLY)) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.quests_form_recurring), style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
            Switch(checked = form.recurring, onCheckedChange = { edit(form.copy(recurring = it)) })
        }
        if (goals.isNotEmpty()) {
            Label(R.string.quests_form_goal)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(form.goalId == null, stringResource(R.string.quests_form_no_goal)) { edit(form.copy(goalId = null)) }
                goals.forEach { goal -> Chip(form.goalId == goal.id, goal.title) { edit(form.copy(goalId = goal.id)) } }
            }
        }
        Text(
            stringResource(R.string.quests_form_xp_preview, form.previewXp.toInt()),
            style = AscentTextStyles.Number,
            color = AscentColors.GlowCyan,
            modifier = Modifier.padding(top = 12.dp),
        )
        GuardWarning(form.paysPercent)
        Buttons(
            canSave = form.valid,
            onCancel = { onEvent(QuestLogEvent.CloseQuestForm) },
            onSave = { onEvent(QuestLogEvent.SaveQuest) },
        )
    }
}

/**
 * The grind guard's System line (PHASE5.md section 2), shown when a quest you add would
 * pay less than full XP if completed now. Nothing when it would pay 100%.
 */
@Composable
internal fun GuardWarning(paysPercent: Int) {
    if (paysPercent >= 100) return
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.quests_guard_line), style = AscentTextStyles.SystemLabel, color = AscentColors.LegendGold)
        Text(stringResource(R.string.quests_guard_pays, paysPercent), style = AscentTextStyles.Number, color = AscentColors.LegendGold)
    }
}

@Composable
fun GoalFormDialog(form: GoalForm, onEvent: (QuestLogEvent) -> Unit) {
    Dialog(onDismissRequest = { onEvent(QuestLogEvent.CloseGoalForm) }) {
        GoalFormPanel(form, onEvent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalFormPanel(form: GoalForm, onEvent: (QuestLogEvent) -> Unit) {
    fun edit(change: GoalForm) = onEvent(QuestLogEvent.EditGoalForm(change))
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.quests_goal_form_header)) {
        OutlinedTextField(
            value = form.title,
            onValueChange = { edit(form.copy(title = it.take(80))) },
            label = { Text(stringResource(R.string.quests_form_title)) },
            singleLine = true,
            isError = form.forbidden,
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.forbidden) {
            Text(stringResource(R.string.quests_form_forbidden), style = MaterialTheme.typography.bodySmall, color = AscentColors.PenaltyRed)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = form.target,
                onValueChange = { edit(form.copy(target = it.take(7))) },
                label = { Text(stringResource(R.string.quests_form_target)) },
                singleLine = true,
                textStyle = AscentTextStyles.Number,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.unit,
                onValueChange = { edit(form.copy(unit = it.take(16))) },
                label = { Text(stringResource(R.string.quests_form_unit)) },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
        }
        Label(R.string.quests_form_realm)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Realm.entries.forEach { realm ->
                Chip(form.realm == realm, stringResource(realm.labelRes())) { edit(form.copy(realm = realm)) }
            }
        }
        Label(R.string.quests_form_horizon)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(form.horizon == GoalHorizon.WEEK, stringResource(R.string.quests_form_week)) { edit(form.copy(horizon = GoalHorizon.WEEK)) }
            Chip(form.horizon == GoalHorizon.LONG, stringResource(R.string.quests_form_long)) { edit(form.copy(horizon = GoalHorizon.LONG)) }
        }
        Buttons(
            canSave = form.valid,
            onCancel = { onEvent(QuestLogEvent.CloseGoalForm) },
            onSave = { onEvent(QuestLogEvent.SaveGoal) },
        )
    }
}

@Composable
private fun Label(res: Int) {
    Text(
        stringResource(res).uppercase(),
        style = AscentTextStyles.SystemLabel,
        color = AscentColors.SystemBlue,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable
private fun Buttons(canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.quests_form_cancel), color = AscentColors.Mist)
        }
        OutlinedButton(onClick = onSave, enabled = canSave, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.quests_form_save), color = AscentColors.GlowCyan)
        }
    }
}

@Preview(name = "Add quest", widthDp = 400, heightDp = 1000)
@Composable
private fun QuestFormPreview() {
    AscentTheme {
        SystemBackground {
            Column(Modifier.padding(16.dp)) {
                QuestFormPanel(
                    QuestForm(title = "Pull-ups", target = "20", unit = "reps", previewXp = 92),
                    SampleQuests.state.goals,
                    onEvent = {},
                )
            }
        }
    }
}

@Preview(name = "Add quest — diminishing returns", widthDp = 400, heightDp = 1000)
@Composable
private fun QuestFormGuardPreview() {
    AscentTheme {
        SystemBackground {
            Column(Modifier.padding(16.dp)) {
                QuestFormPanel(
                    QuestForm(title = "Answer the group's emails", target = "1", unit = "done", previewXp = 23, paysPercent = 25),
                    emptyList(),
                    onEvent = {},
                )
            }
        }
    }
}

@Preview(name = "Add goal", widthDp = 400, heightDp = 700)
@Composable
private fun GoalFormPreview() {
    AscentTheme {
        SystemBackground {
            Column(Modifier.padding(16.dp)) {
                GoalFormPanel(GoalForm(title = "Finish KPZ scaling figures", target = "3", unit = "figures"), onEvent = {})
            }
        }
    }
}
