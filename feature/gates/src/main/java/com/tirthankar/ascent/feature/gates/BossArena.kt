package com.tirthankar.ascent.feature.gates

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.db.BossStatus
import com.tirthankar.ascent.data.model.Boss
import com.tirthankar.ascent.data.model.BossTask
import com.tirthankar.ascent.data.repo.BossRepository
import com.tirthankar.ascent.data.repo.NewBossTask
import com.tirthankar.ascent.data.db.CastTargetKind
import com.tirthankar.ascent.data.repo.CastOption
import com.tirthankar.ascent.data.repo.CastResult
import com.tirthankar.ascent.data.repo.PowerEffects
import com.tirthankar.ascent.data.repo.PowerRepository
import com.tirthankar.ascent.feature.skills.CastPickerSheet
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.BossHpBar
import com.tirthankar.ascent.designsystem.HexagonCheck
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.designsystem.XpFloat
import com.tirthankar.ascent.engine.BossRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArenaCast(val taskId: Long, val title: String, val options: List<CastOption>)

/** The last hit, for the damage number. */
data class HitUi(val damage: Int, val xp: Long, val critical: Boolean, val key: Long)

data class TaskForm(val title: String = "", val hours: String = "1", val phase: Int = 0) {
    val hoursValue: Double? get() = hours.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val valid: Boolean get() = title.isNotBlank() && hoursValue != null
}

data class BossArenaUiState(
    val boss: Boss? = null,
    val today: Long = 0,
    val hit: HitUi? = null,
    val taskForm: TaskForm? = null,
    val trialResult: String = "",
    /** The cast picker for a task (Phase 4): task id, title and the powers that take it. */
    val cast: ArenaCast? = null,
) {
    /** Phase markers on the HP bar: where each phase's damage ends, as a fraction of max HP. */
    val markers: List<Float>
        get() {
            val b = boss ?: return emptyList()
            if (b.maxHp <= 0) return emptyList()
            var remaining = b.maxHp
            return (0 until b.phases.size - 1).map { phase ->
                remaining -= b.tasks.filter { it.phase == phase }.sumOf { it.damage }
                remaining.toFloat() / b.maxHp
            }
        }
}

sealed interface ArenaEvent {
    data class Hit(val taskId: Long) : ArenaEvent
    data class Undo(val taskId: Long) : ArenaEvent
    data object HitShown : ArenaEvent
    data object OpenTaskForm : ArenaEvent
    data class EditTaskForm(val form: TaskForm) : ArenaEvent
    data object SaveTask : ArenaEvent
    data object CloseTaskForm : ArenaEvent
    data class TrialResult(val text: String) : ArenaEvent
    data object SubmitTrial : ArenaEvent
    data object MarkPaper : ArenaEvent
    data object Abandon : ArenaEvent
    data class OpenCast(val taskId: Long) : ArenaEvent
    data class Cast(val skillId: String) : ArenaEvent
    data object CloseCast : ArenaEvent
}

@HiltViewModel
class BossArenaViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val bosses: BossRepository,
    private val powers: PowerRepository,
    private val effects: PowerEffects,
    gameDay: GameDay,
    events: GameEvents,
) : ViewModel() {

    private val bossId: Long = savedState.get<Long>("bossId") ?: 0L
    private val local = MutableStateFlow(BossArenaUiState())

    val uiState: StateFlow<BossArenaUiState> =
        combine(bosses.observe(bossId), gameDay.observeToday(), local) { boss, today, l ->
            l.copy(boss = boss, today = today.toEpochDay())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BossArenaUiState())

    init {
        events.events.filterIsInstance<GameEvent.BossHit>()
            .onEach { e -> if (e.bossId == bossId) local.update { it.copy(hit = HitUi(e.damage, e.xp, e.critical, System.nanoTime())) } }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: ArenaEvent) {
        when (event) {
            is ArenaEvent.Hit -> viewModelScope.launch { bosses.hit(event.taskId) }
            is ArenaEvent.Undo -> viewModelScope.launch { bosses.undoHit(event.taskId) }
            ArenaEvent.HitShown -> local.update { it.copy(hit = null) }
            ArenaEvent.OpenTaskForm -> local.update { it.copy(taskForm = TaskForm(phase = (uiState.value.boss?.phases?.lastIndex ?: 0).coerceAtLeast(0))) }
            is ArenaEvent.EditTaskForm -> local.update { it.copy(taskForm = event.form) }
            ArenaEvent.CloseTaskForm -> local.update { it.copy(taskForm = null) }
            ArenaEvent.SaveTask -> viewModelScope.launch {
                val form = local.value.taskForm ?: return@launch
                val hours = form.hoursValue ?: return@launch
                if (bosses.addTask(bossId, form.phase, NewBossTask(form.title, hours))) local.update { it.copy(taskForm = null) }
            }
            is ArenaEvent.TrialResult -> local.update { it.copy(trialResult = event.text) }
            ArenaEvent.SubmitTrial -> viewModelScope.launch {
                val result = local.value.trialResult.trim().replace(',', '.').toDoubleOrNull() ?: return@launch
                if (bosses.attemptTrial(bossId, result)) local.update { it.copy(trialResult = "") }
            }
            ArenaEvent.MarkPaper -> viewModelScope.launch { bosses.markPaperSubmitted(bossId) }
            ArenaEvent.Abandon -> viewModelScope.launch { bosses.abandon(bossId) }
            is ArenaEvent.OpenCast -> viewModelScope.launch { openCast(event.taskId) }
            is ArenaEvent.Cast -> viewModelScope.launch {
                val pick = local.value.cast ?: return@launch
                when (effects.cast(event.skillId, CastTargetKind.BOSS_TASK, pick.taskId)) {
                    is CastResult.Cast -> local.update { it.copy(cast = null) }
                    is CastResult.Refused -> openCast(pick.taskId)
                }
            }
            ArenaEvent.CloseCast -> local.update { it.copy(cast = null) }
        }
    }

    private suspend fun openCast(taskId: Long) {
        val task = uiState.value.boss?.tasks?.firstOrNull { it.id == taskId } ?: return
        if (task.done || !task.unlocked) return
        local.update { it.copy(cast = ArenaCast(taskId, task.title, powers.optionsFor(CastTargetKind.BOSS_TASK, taskId))) }
    }
}

@Composable
fun BossArenaRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: BossArenaViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Materialize(modifier) { BossArenaScreen(state, viewModel::onEvent, onBack) }
    state.taskForm?.let { TaskFormDialog(it, state.boss?.phases.orEmpty(), viewModel::onEvent) }
    state.cast?.let { pick ->
        CastPickerSheet(
            targetTitle = pick.title,
            options = pick.options,
            onCast = { id, _ -> viewModel.onEvent(ArenaEvent.Cast(id)) },
            onDismiss = { viewModel.onEvent(ArenaEvent.CloseCast) },
        )
    }
}

/** The Boss Arena (Tasks 25–27): HP bar with phases, tasks as attacks, Trials. Stateless. */
@Composable
fun BossArenaScreen(state: BossArenaUiState, onEvent: (ArenaEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("← " + stringResource(R.string.arena_back), color = AscentColors.GlowCyan)
        }
        val boss = state.boss ?: return@Column
        if (boss.isTrial) {
            TrialPanel(boss, state, onEvent)
            return@Column
        }
        HoloPanel(Modifier.fillMaxWidth(), header = boss.title, accent = AscentColors.PenaltyRed) {
            Box {
                BossHpBar(stringResource(R.string.boss_hp), boss.hp, boss.maxHp, state.markers)
                state.hit?.let { hit ->
                    XpFloat(
                        text = stringResource(if (hit.critical) R.string.arena_hit_critical else R.string.arena_hit, hit.damage, hit.xp.toInt()),
                        key = hit.key,
                        onFinished = { onEvent(ArenaEvent.HitShown) },
                        color = if (hit.critical) AscentColors.LegendGold else AscentColors.PenaltyRed,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
            val deadline = boss.deadlineDay?.let { it - state.today }
            val lines = listOfNotNull(
                boss.publicId,
                stringResource(R.string.boss_raid).takeIf { boss.isRaid },
                deadline?.let { if (it >= 0) pluralStringResource(R.plurals.boss_deadline_days, it.toInt(), it.toInt()) else stringResource(R.string.boss_enraged) },
                stringResource(R.string.arena_status_defeated).takeIf { boss.status == BossStatus.DEFEATED },
            )
            Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (boss.enraged) AscentColors.PenaltyRed else AscentColors.Mist, modifier = Modifier.padding(top = 6.dp))
        }
        boss.phases.forEachIndexed { index, name ->
            val tasks = boss.tasks.filter { it.phase == index }
            val label = when {
                index < boss.currentPhase -> stringResource(R.string.arena_phase_cleared)
                index == boss.currentPhase -> stringResource(R.string.arena_phase_open)
                else -> stringResource(R.string.arena_phase_locked)
            }
            HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.arena_phase, index + 1, name), glow = index == boss.currentPhase) {
                Text(label, style = AscentTextStyles.SystemLabel, color = if (index == boss.currentPhase) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.6f))
                if (tasks.isEmpty()) Body(stringResource(R.string.arena_no_tasks))
                tasks.forEach { TaskRow(it, onEvent) }
            }
        }
        if (boss.status == BossStatus.ACTIVE) {
            OutlinedButton(onClick = { onEvent(ArenaEvent.OpenTaskForm) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("+ " + stringResource(R.string.arena_add_task), color = AscentColors.GlowCyan)
            }
            TextButton(onClick = { onEvent(ArenaEvent.Abandon) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.arena_abandon), color = AscentColors.Mist.copy(alpha = 0.7f))
            }
        }
        if (boss.status == BossStatus.DEFEATED && !boss.paperSubmitted) {
            OutlinedButton(onClick = { onEvent(ArenaEvent.MarkPaper) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.arena_mark_paper), color = AscentColors.LegendGold)
            }
        }
    }
}

@Composable
private fun TaskRow(task: BossTask, onEvent: (ArenaEvent) -> Unit) {
    val description = when {
        !task.done -> stringResource(R.string.arena_hit_description, task.title, task.damage)
        task.canUndo -> stringResource(R.string.arena_undo_description, task.title)
        else -> stringResource(R.string.arena_done_description, task.title)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HexagonCheck(
            checked = task.done,
            onToggle = { onEvent(if (task.done) ArenaEvent.Undo(task.id) else ArenaEvent.Hit(task.id)) },
            contentDescription = description,
            enabled = (!task.done && task.unlocked) || task.canUndo,
        )
        val castLabel = stringResource(R.string.arena_cast_on)
        val castable = !task.done && task.unlocked
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .then(
                    if (castable) {
                        // Long-press casts a power on this task (Eigenstate Collapse, A8); TalkBack gets an action.
                        Modifier
                            .pointerInput(task.id) { detectTapGestures(onLongPress = { onEvent(ArenaEvent.OpenCast(task.id)) }) }
                            .semantics { customActions = listOf(CustomAccessibilityAction(castLabel) { onEvent(ArenaEvent.OpenCast(task.id)); true }) }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, color = if (task.unlocked) AscentColors.Mist else AscentColors.Mist.copy(alpha = 0.5f))
            Text(
                "${task.publicId} · ${number(task.hours)} h · " + stringResource(R.string.arena_damage, task.damage) +
                    (if (task.critical) " · " + stringResource(R.string.arena_critical) else ""),
                style = AscentTextStyles.Number,
                color = AscentColors.Mist.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun TrialPanel(boss: Boss, state: BossArenaUiState, onEvent: (ArenaEvent) -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = boss.title, accent = AscentColors.LegendGold) {
        Body(stringResource(R.string.arena_trial_intro))
        Text(stringResource(R.string.gates_trial_record, number(boss.recordToBeat ?: 0.0)), style = AscentTextStyles.NumberLarge, color = AscentColors.LegendGold)
        boss.lastResult?.let { Body(stringResource(R.string.arena_trial_last, number(it))) }
        when {
            boss.status == BossStatus.WON -> Body(stringResource(R.string.arena_trial_won), AscentColors.GlowCyan)
            boss.trialOpen -> {
                OutlinedTextField(
                    state.trialResult,
                    { onEvent(ArenaEvent.TrialResult(it)) },
                    label = { Text(stringResource(R.string.arena_trial_result)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(onClick = { onEvent(ArenaEvent.SubmitTrial) }, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.arena_trial_submit), color = AscentColors.LegendGold)
                }
            }
            else -> Body(stringResource(R.string.arena_trial_wait, BossRules.TRIAL_REOPEN_DAYS.toInt()))
        }
    }
}

@Composable
private fun TaskFormDialog(form: TaskForm, phases: List<String>, onEvent: (ArenaEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(ArenaEvent.CloseTaskForm) },
        title = { Text(stringResource(R.string.arena_add_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.title, { onEvent(ArenaEvent.EditTaskForm(form.copy(title = it))) }, label = { Text(stringResource(R.string.arena_task_title)) }, singleLine = true)
                OutlinedTextField(
                    form.hours,
                    { onEvent(ArenaEvent.EditTaskForm(form.copy(hours = it))) },
                    label = { Text(stringResource(R.string.arena_task_hours)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.arena_task_phase, form.phase + 1, phases.getOrNull(form.phase) ?: stringResource(R.string.arena_new_phase)), modifier = Modifier.weight(1f))
                    TextButton(onClick = { onEvent(ArenaEvent.EditTaskForm(form.copy(phase = (form.phase - 1).coerceAtLeast(0)))) }) { Text("−") }
                    TextButton(onClick = { onEvent(ArenaEvent.EditTaskForm(form.copy(phase = (form.phase + 1).coerceAtMost(minOf(phases.size, BossRules.MAX_PHASES - 1))))) }) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onEvent(ArenaEvent.SaveTask) }, enabled = form.valid) { Text(stringResource(R.string.boss_form_save)) } },
        dismissButton = { TextButton(onClick = { onEvent(ArenaEvent.CloseTaskForm) }) { Text(stringResource(R.string.boss_form_cancel)) } },
    )
}

// ---- Previews ----

private val sampleBoss = Boss(
    1, "b1", "The Draft", null, 20_540, false, false, null, null, null, null, BossStatus.ACTIVE,
    listOf("Calculations", "Figures", "Writing"),
    listOf(
        BossTask(1, "t1", 0, "Write up the BMFT cumulant calculation", 6.0, 600, true, true, true, false),
        BossTask(2, "t2", 0, "Check the small-density limit", 2.0, 200, false, true, false, false),
        BossTask(3, "t3", 1, "Make figure 3", 3.0, 300, false, false, false, false),
        BossTask(4, "t4", 2, "Draft the results section", 8.0, 800, false, false, false, false),
    ),
    1_900, 1_300, 0, false, false, false,
)

@Preview(name = "Boss Arena", widthDp = 400, heightDp = 1200)
@Composable
private fun BossArenaPreview() {
    AscentTheme { SystemBackground { BossArenaScreen(BossArenaUiState(sampleBoss, today = 20_420), {}, {}) } }
}

@Preview(name = "Boss Arena — Trial", widthDp = 400, heightDp = 700)
@Composable
private fun TrialPreview() {
    val trial = sampleBoss.copy(title = "Trial: Push-ups", isTrial = true, recordToBeat = 15.0, lastResult = 14.0, trialOpen = true, tasks = emptyList(), phases = emptyList())
    AscentTheme { SystemBackground { BossArenaScreen(BossArenaUiState(trial, today = 20_420, trialResult = "16"), {}, {}) } }
}
