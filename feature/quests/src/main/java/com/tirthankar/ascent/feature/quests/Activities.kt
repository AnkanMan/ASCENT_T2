package com.tirthankar.ascent.feature.quests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.db.*
import com.tirthankar.ascent.data.repo.*
import com.tirthankar.ascent.designsystem.*
import com.tirthankar.ascent.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject

data class TaskSelection(val questId: Long? = null, val itemId: Long? = null)
data class ActivitiesUi(
    val data: ActivitySnapshot? = null,
    val task: TaskSelection? = null,
    val busy: Boolean = false,
    val message: Int? = null,
    val seconds: Map<String, Long> = emptyMap(),
)

interface ActivityActions {
    fun closePlanner()
    fun closeTask()
    fun openItem(id: Long)
    fun savePlan(day: Long, items: List<PlannedTask>)
    fun bind(item: Long, quest: Long)
    fun mode(id: Long, mode: String)
    fun start(manual: Boolean)
    fun control(id: String, command: String)
    fun saveResult(id: String, value: Double, adjusted: Boolean, note: String)
    fun remove(id: String)
}

@HiltViewModel
class ActivitiesViewModel @Inject constructor(private val repository: ActivityRepository) : ViewModel(), ActivityActions {
    private val mutable = MutableStateFlow(ActivitiesUi())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    init {
        viewModelScope.launch {
            mutable.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
                if (visible) while (true) {
                    mutex.withLock { refresh() }
                    delay(1000)
                }
            }
        }
    }

    private suspend fun refresh() {
        val data = repository.snapshot()
        val before = mutable.value.data
        val newlyComplete = before != null && data.plans.any { p -> p.completedAt != null && before.plans.any { it.id == p.id && it.completedAt == null } }
        mutable.value = mutable.value.copy(data = data, seconds = data.sessions.associate { it.id to repository.seconds(it) },
            message = if (newlyComplete) R.string.planner_complete_xp else if (before != null && data.plans.any { p -> before.plans.none { it.id == p.id } }) R.string.planner_written_xp else mutable.value.message)
    }

    fun selectTask(selection: TaskSelection) { mutable.value = mutable.value.copy(task = selection) }
    override fun openItem(id: Long) {}
    override fun closeTask() {}
    override fun closePlanner() {}
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }

    private fun action(block: suspend () -> Boolean) {
        viewModelScope.launch {
            mutex.withLock {
                mutable.value = mutable.value.copy(busy = true)
                try {
                    val ok = block()
                    mutable.value = mutable.value.copy(message = if (ok) R.string.activity_saved else R.string.activity_refused)
                    refresh()
                } finally {
                    mutable.value = mutable.value.copy(busy = false)
                }
            }
        }
    }

    override fun savePlan(day: Long, items: List<PlannedTask>) = action { repository.saveTomorrow(day, items) }
    override fun bind(item: Long, quest: Long) = action { repository.bind(item, quest) }
    override fun mode(id: Long, mode: String) = action { repository.setMode(id, mode) }
    override fun start(manual: Boolean) = action {
        val owner = mutable.value.task ?: return@action false
        val id = repository.start(owner.questId, owner.itemId, manual) ?: return@action false
        if (manual) repository.control(id, "FINISH") else true
    }
    override fun control(id: String, command: String) = action { repository.control(id, command) }
    override fun saveResult(id: String, value: Double, adjusted: Boolean, note: String) = action { repository.saveResult(id, value, adjusted, note) }
    override fun remove(id: String) = action { repository.removeResult(id) }

}

/** Shared entry and resume affordance in the Quest Log; pages live in the app back stack. */
@Composable
internal fun ActivityHub(
    onOpenPlanner: () -> Unit,
    onOpenTask: (TaskSelection) -> Unit,
    onOpenStudy: () -> Unit,
    viewModel: ActivitiesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudyEntry(onOpenStudy)
        PlannerEntry(onOpenPlanner)
        state.data?.sessions?.firstOrNull { it.state in listOf("RUNNING", "PAUSED", "RESULT") && !(state.data?.studyActive == true && it.questId == state.data?.studyQuestId) }?.let { session ->
            OutlinedButton(onClick = { onOpenTask(TaskSelection(session.questId, session.itemId)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.activity_resume_banner, duration(state.seconds[session.id] ?: session.activeSeconds)))
            }
        }
    }
}

@Composable
private fun PlannerEntry(onClick: () -> Unit) {
    HoloPanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlannerEmblem()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.planner_title), color = AscentColors.SystemBlue,
                    style = MaterialTheme.typography.headlineMedium.copy(shadow = Shadow(AscentColors.SystemBlue, blurRadius = 14f)))
                Text(stringResource(R.string.planner_entry_hint), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist)
            }
            Text("›", style = MaterialTheme.typography.headlineLarge, color = AscentColors.GlowCyan)
        }
    }
}

/** Calendar with checklist marks, drawn locally to match the System's geometric icon style. */
@Composable
private fun PlannerEmblem() {
    val blue = AscentColors.SystemBlue
    val cyan = AscentColors.GlowCyan
    Canvas(Modifier.size(48.dp)) {
        val stroke = 2.dp.toPx()
        drawRect(blue, Offset(size.width * .12f, size.height * .18f), Size(size.width * .76f, size.height * .72f), style = Stroke(stroke))
        drawLine(blue, Offset(size.width * .12f, size.height * .36f), Offset(size.width * .88f, size.height * .36f), stroke)
        for (x in listOf(.32f, .68f)) drawLine(cyan, Offset(size.width * x, size.height * .08f), Offset(size.width * x, size.height * .26f), stroke)
        for (y in listOf(.52f, .72f)) {
            drawLine(cyan, Offset(size.width * .26f, size.height * y), Offset(size.width * .36f, size.height * (y + .06f)), stroke)
            drawLine(cyan, Offset(size.width * .36f, size.height * (y + .06f)), Offset(size.width * .48f, size.height * (y - .06f)), stroke)
            drawLine(blue, Offset(size.width * .59f, size.height * y), Offset(size.width * .74f, size.height * y), stroke)
        }
    }
}

@Composable
fun PlannerRoute(onBack: () -> Unit, onOpenTask: (TaskSelection) -> Unit, viewModel: ActivitiesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = object : ActivityActions by viewModel {
        override fun closePlanner() = onBack()
        override fun openItem(id: Long) {
            val item = state.data?.items?.find { it.id == id } ?: return
            onOpenTask(TaskSelection(item.questId, id.takeIf { item.questId == null }))
        }
    }
    PlannerScreen(state, actions)
}

@Composable
fun TaskActivityRoute(questId: Long?, itemId: Long?, onBack: () -> Unit, onOpenStudy: () -> Unit = {}, viewModel: ActivitiesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(questId, itemId) { viewModel.selectTask(TaskSelection(questId, itemId)) }
    val actions = object : ActivityActions by viewModel { override fun closeTask() = onBack() }
    TaskScreen(state, actions, onOpenStudy)
}

@Composable
internal fun ActivityPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.activity_back)) }
            Text(title, style = AscentTextStyles.SystemHeader, color = AscentColors.GlowCyan, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
internal fun ActivityPanel(header: String? = null, content: @Composable ColumnScope.() -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = header) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private val PlannedTasksSaver = listSaver<List<PlannedTask>, String>(
    save = { tasks -> tasks.flatMap { listOf(it.title, it.mode, it.target.toString(), it.unit) } },
    restore = { values -> values.chunked(4).map { PlannedTask(it[0], it[1], it[2].toDouble(), it[3]) } },
)

@Composable
private fun PlannerScreen(state: ActivitiesUi, vm: ActivityActions, initialTab: Int = 0) {
    val data = state.data
    if (data == null) {
        ActivityPage(stringResource(R.string.planner_page_title), vm::closePlanner) { CircularProgressIndicator() }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val tomorrow = data.today + 1
    val existing = data.plans.find { it.targetDay == tomorrow }
    var draftDay by rememberSaveable { mutableLongStateOf(tomorrow) }
    var draft by rememberSaveable(stateSaver = PlannedTasksSaver) { mutableStateOf(data.items.filter { it.planId == existing?.id }.map { PlannedTask(it.title, it.mode, it.target, it.unit) }) }
    val persistedDraft = data.items.filter { it.planId == existing?.id }.map { PlannedTask(it.title, it.mode, it.target, it.unit) }
    val dirty = draft != persistedDraft
    var text by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(MeasurementMode.CHECKBOX) }
    var target by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var binding by rememberSaveable { mutableStateOf<Long?>(null) }
    // Never silently retarget an unsaved editor when the reset occurs.
    val stale = draftDay != tomorrow
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val leave = { if (dirty || text.isNotBlank()) confirmLeave = true else vm.closePlanner() }
    BackHandler { leave() }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.planner_discard_title)) },
        text = { Text(stringResource(R.string.planner_discard_body)) },
        confirmButton = { TextButton(onClick = vm::closePlanner) { Text(stringResource(R.string.planner_discard)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.planner_keep_editing)) } },
    )
    ActivityPage(stringResource(R.string.planner_page_title), leave) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = AscentColors.GlowCyan) {
            listOf(R.string.planner_today, R.string.planner_tomorrow, R.string.planner_history).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label), style = AscentTextStyles.SystemLabel) })
            }
        }
        if (tab == 1) {
            ActivityPanel(stringResource(R.string.planner_briefing)) {
                Text(LocalDate.ofEpochDay(draftDay).toString(), style = MaterialTheme.typography.headlineLarge, color = AscentColors.GlowCyan)
                Text(stringResource(R.string.planner_rules), color = AscentColors.Mist)
                Text(stringResource(R.string.activity_period_end, java.time.Instant.ofEpochMilli(data.nextReset).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString()), style = AscentTextStyles.Number)
                if (existing != null) Text(stringResource(R.string.planner_saved_bonus), color = AscentColors.GlowCyan)
            }
            if (stale) {
                Text(stringResource(R.string.activity_refused))
                TextButton(onClick = { draftDay = tomorrow; draft = persistedDraft; text = ""; editing = null }) { Text(stringResource(R.string.planner_reload)) }
            }
            if (draft.isEmpty()) Text(stringResource(R.string.planner_no_draft), color = AscentColors.Mist)
            draft.forEachIndexed { index, item ->
                ActivityPanel {
                    Text(stringResource(R.string.planner_draft_item, index + 1, item.title, item.target.toString(), item.unit), style = MaterialTheme.typography.titleLarge, color = AscentColors.GlowCyan)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { editing = index; text = item.title; mode = MeasurementMode.valueOf(item.mode); target = item.target.toString(); unit = item.unit }) { Text(stringResource(R.string.activity_edit)) }
                        TextButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index }; editing = null; text = "" }) { Text(stringResource(R.string.activity_remove)) }
                        if (index > 0) TextButton(onClick = {
                                draft = draft.toMutableList().also { val previous = it[index - 1]; it[index - 1] = it[index]; it[index] = previous }
                                editing = when (editing) { index -> index - 1; index - 1 -> index; else -> editing }
                        }) { Text(stringResource(R.string.planner_move_up)) }
                    }
                }
            }
            ActivityPanel(stringResource(if (editing == null) R.string.planner_editor else R.string.planner_edit_item)) {
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.activity_task_title)) })
                ModePicker(mode) { mode = it }
                if (mode != MeasurementMode.CHECKBOX) {
                    OutlinedTextField(target, { target = it }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text(stringResource(R.string.activity_target)) })
                    OutlinedTextField(unit, { unit = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.activity_unit)) })
                    if (mode == MeasurementMode.DURATION) Text(stringResource(R.string.activity_duration_units))
                }
                val amount = if (mode == MeasurementMode.CHECKBOX) 1.0 else target.toDoubleOrNull()
                val validTarget = amount != null && amount.isFinite() && amount > 0 && (mode != MeasurementMode.COUNT || amount == kotlin.math.floor(amount))
                val validUnit = mode != MeasurementMode.DURATION || TargetUnit.fromLabel(unit) in listOf(TargetUnit.SECONDS, TargetUnit.MINUTES, TargetUnit.HOURS)
                if (!validTarget || !validUnit) Text(stringResource(R.string.planner_invalid_target), color = AscentColors.PenaltyRed)
                OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = text.isNotBlank() && !SafetyRules.isForbidden(text) && validTarget && validUnit && !stale && !state.busy, onClick = {
                        if (amount != null && amount.isFinite() && amount > 0 && !SafetyRules.isForbidden(text)) {
                            val item = PlannedTask(text.trim(), mode.name, amount, if (mode == MeasurementMode.CHECKBOX) "done" else unit)
                            draft = draft.toMutableList().also { list -> editing?.let { list[it] = item } ?: list.add(item) }
                            text = ""; editing = null
                        }
                }) { Text(stringResource(if (editing == null) R.string.planner_add else R.string.planner_edit_save)) }
            }
            if (text.isNotBlank()) Text(stringResource(R.string.planner_add_before_save), color = AscentColors.Mist)
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = text.isBlank() && !stale && draft.isNotEmpty() && !state.busy && (dirty || existing == null), onClick = { vm.savePlan(draftDay, draft) }) {
                Text(stringResource(if (existing == null) R.string.planner_save_xp else R.string.planner_save_changes))
            }
        } else {
            val plans = if (tab == 0) data.plans.filter { it.targetDay == data.today } else data.plans.filter { it.targetDay < data.today }
            if (plans.isEmpty()) ActivityPanel { Text(stringResource(R.string.planner_empty), style = MaterialTheme.typography.bodyLarge) }
            plans.forEach { plan ->
                val items = data.items.filter { it.planId == plan.id }
                val complete = items.count { item -> item.questId?.let { q -> data.quests.find { it.id == q }?.status == QuestStatus.DONE } ?: item.done }
                ActivityPanel {
                    Text(stringResource(R.string.planner_summary, LocalDate.ofEpochDay(plan.targetDay).toString(), complete, items.size, stateLabel(plan.state)))
                    SystemProgressBar(stringResource(R.string.activity_objective), complete.toLong(), items.size.toLong(), SystemBarKind.XP)
                    Text(stringResource(if (plan.completedAt == null) R.string.planner_reward_hint else R.string.planner_complete_xp))
                    items.forEach { item ->
                        OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !state.busy, onClick = { vm.openItem(item.id) }) {
                            Text(item.title + (item.questId?.let { id -> data.quests.find { it.id == id }?.let { " · ${it.title}" } } ?: ""))
                        }
                        if (tab == 0 && item.questId == null && item.progress == 0.0 && !item.done && plan.completedAt == null) {
                            TextButton(onClick = { binding = item.id }) { Text(stringResource(R.string.planner_link)) }
                        }
                        if (binding == item.id) {
                            data.quests.filter { it.day == data.today && it.scope == QuestScope.DAILY && it.kind == QuestKind.NORMAL && it.status == QuestStatus.OPEN && items.none { item -> item.questId == it.id } }.forEach { q ->
                                TextButton(onClick = { vm.bind(item.id, q.id); binding = null }) { Text(q.title) }
                            }
                        }
                    }
                }
            }
        }
        state.message?.let { Text(stringResource(it)) }
    }
}

@Composable
private fun TaskScreen(state: ActivitiesUi, vm: ActivityActions, onOpenStudy: () -> Unit = {}) {
    val data = state.data
    val owner = state.task
    if (data == null || owner == null) {
        ActivityPage(stringResource(R.string.activity_open), vm::closeTask) { CircularProgressIndicator() }
        return
    }
    val quest = data.quests.find { it.id == owner.questId }
    val item = data.items.find { it.id == owner.itemId }
    if (quest == null && item == null) {
        ActivityPage(stringResource(R.string.activity_open), vm::closeTask) { Text(stringResource(R.string.activity_inactive)) }
        return
    }
    val singleFocus = quest?.let { PlannerRules.singleSessionFocus(it.templateKey, it.scope == QuestScope.DAILY) } == true
    val mode = if (singleFocus) MeasurementMode.DURATION else quest?.let { if (it.measurementMode == "AUTO") PlannerRules.mode(it.unit) else MeasurementMode.valueOf(it.measurementMode) }
    ?: MeasurementMode.valueOf(item!!.mode)
    val title = quest?.title ?: item!!.title
    val target = quest?.target ?: item!!.target
    val progress = quest?.actualProgress ?: item!!.progress
    val unit = quest?.unitLabel ?: item!!.unit
    val history = data.sessions.filter { it.questId == owner.questId && it.itemId == owner.itemId }
    val session = history.firstOrNull { it.state in listOf("RUNNING", "PAUSED", "RESULT") }
    val seconds = session?.let { state.seconds[it.id] } ?: 0
    val measured = PlannerRules.quantity(seconds, TargetUnit.fromLabel(unit))
    var result by rememberSaveable(session?.id, session?.state) { mutableStateOf(if (mode == MeasurementMode.DURATION) measured.toString() else "") }
    var note by rememberSaveable(session?.id) { mutableStateOf("") }
    val plan = item?.let { i -> data.plans.find { it.id == i.planId } }
    val active = if (quest != null) quest.status == QuestStatus.OPEN && quest.day == (if (quest.scope == QuestScope.DAILY) data.today else GameClock.weekStart(LocalDate.ofEpochDay(data.today)).toEpochDay())
    else plan?.targetDay == data.today && plan.completedAt == null && item?.done == false
    val studyManaged = quest != null && data.studyActive && data.studyQuestId == quest.id
    val supported = quest == null || quest.kind == QuestKind.NORMAL
    val reset = java.time.Instant.ofEpochMilli(data.nextReset).atZone(java.time.ZoneId.systemDefault())
    val expiry = session?.endsAt ?: if (quest?.scope == QuestScope.WEEKLY) LocalDate.ofEpochDay(quest.day + 7).atTime(reset.toLocalTime()).atZone(reset.zone).toInstant().toEpochMilli() else data.nextReset
    val otherRunning = data.sessions.any { it.state == "RUNNING" && (it.questId != owner.questId || it.itemId != owner.itemId) }
    val blockingFocus = data.sessions.any { s -> s.state == "RUNNING" && (s.questId != owner.questId || s.itemId != owner.itemId) && data.quests.find { it.id == s.questId }?.let { PlannerRules.singleSessionFocus(it.templateKey, it.scope == QuestScope.DAILY) } == true }
    ActivityPage(stringResource(R.string.activity_page_title), vm::closeTask) {
        ActivityPanel(stringResource(R.string.activity_objective)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = AscentColors.GlowCyan)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { (progress / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = AscentColors.GlowCyan, trackColor = AscentColors.SystemBlue.copy(alpha = .15f))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.activity_progress, progress.toString(), target.toString(), unit), style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
            Text(stringResource(R.string.activity_period_end, java.time.Instant.ofEpochMilli(expiry).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString()))
            Text(stringResource(if (singleFocus) R.string.activity_focus_single else if (quest != null) R.string.activity_quest_rewards else R.string.activity_planner_rewards))
        }
        if (!active) Text(stringResource(R.string.activity_inactive))
        if (!singleFocus && quest != null && active && history.isEmpty() && progress == 0.0) ModePicker(mode) { vm.mode(quest.id, it.name) }
        if (studyManaged) {
            Text(stringResource(R.string.study_managed_task), color = AscentColors.GlowCyan)
            Button(onClick = onOpenStudy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.study_open)) }
        } else if (session != null) {
            SessionPanel(session.state, duration(seconds), state.busy, { vm.control(session.id, it) }, singleFocus = singleFocus, canResume = !blockingFocus && !data.studyActive)
            if (session.note in listOf("CLOCK_RECOVERY", "RESTORE_RECOVERY")) Text(stringResource(R.string.activity_recovered))
            if (session.state == "RESULT" && active) {
                ActivityPanel(stringResource(R.string.activity_result_header)) {
                    if (mode == MeasurementMode.CHECKBOX) Text(stringResource(R.string.activity_confirm_done)) else {
                        OutlinedTextField(result, { result = it }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = if (mode == MeasurementMode.COUNT) KeyboardType.Number else KeyboardType.Decimal), label = { Text(stringResource(R.string.activity_result, unit)) })
                    }
                    OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.activity_note)) })
                    val amount = if (mode == MeasurementMode.CHECKBOX) target else result.toDoubleOrNull()
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !state.busy && amount != null && PlannerRules.validResult(amount, mode) && (!singleFocus || amount >= target), onClick = {
                            vm.saveResult(session.id, amount!!, mode == MeasurementMode.DURATION && amount != measured, note)
                    }) { Text(stringResource(R.string.activity_save)) }
                }
            }
        } else if (active && supported) {
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), onClick = { vm.start(false) }, enabled = !state.busy && !blockingFocus && !data.studyActive) { Text(stringResource(if (otherRunning) R.string.activity_start_other else R.string.activity_start)) }
            OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { vm.start(true) }, enabled = !state.busy && !data.studyActive) { Text(stringResource(R.string.activity_log)) }
        }
        if (data.studyActive && !studyManaged) Text(stringResource(R.string.study_blocks_timer), color = AscentColors.GlowCyan)
        if (blockingFocus) Text(stringResource(R.string.activity_focus_blocking), color = AscentColors.GlowCyan)
        SystemHeader(stringResource(R.string.activity_history))
        if (history.isEmpty()) Text(stringResource(R.string.activity_no_history), color = AscentColors.Mist)
        history.forEach { h ->
            ActivityPanel {
                Text(stringResource(R.string.activity_session_row, stateLabel(h.state), h.quantity.toString(), unit, duration(state.seconds[h.id] ?: h.activeSeconds), stringResource(if (h.origin == "MANUAL") R.string.activity_manual else R.string.activity_timer)))
                if (h.note.isNotBlank() && h.note !in listOf("CLOCK_RECOVERY", "RESTORE_RECOVERY")) Text(h.note)
                if (h.state == "SAVED") TextButton(onClick = { vm.remove(h.id) }, enabled = !state.busy) { Text(stringResource(R.string.activity_remove_result)) }
            }
        }
        state.message?.let { Text(stringResource(it)) }
    }
}

@Composable
private fun stateLabel(state: String): String = stringResource(when (state) {
        "SCHEDULED" -> R.string.activity_scheduled
        "ACTIVE" -> R.string.activity_active
        "COMPLETED" -> R.string.activity_completed
        "EXPIRED" -> R.string.activity_expired
        "NEUTRAL" -> R.string.activity_neutral
        "RUNNING" -> R.string.activity_running
        "PAUSED" -> R.string.activity_paused
        "RESULT" -> R.string.activity_pending_result
        "SAVED" -> R.string.activity_recorded
        else -> R.string.activity_discarded
})

@Composable
private fun ModePicker(selected: MeasurementMode, change: (MeasurementMode) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MeasurementMode.entries.forEach { mode ->
            FilterChip(selected == mode, onClick = { change(mode) }, label = { Text(stringResource(when (mode) {
                                MeasurementMode.CHECKBOX -> R.string.activity_mode_checkbox
                                MeasurementMode.COUNT -> R.string.activity_mode_count
                                MeasurementMode.DURATION -> R.string.activity_mode_duration
                                MeasurementMode.QUANTITY -> R.string.activity_mode_quantity
            })) })
        }
    }
}

@Composable
private fun SessionPanel(state: String, elapsed: String, busy: Boolean, control: (String) -> Unit, singleFocus: Boolean = false, canResume: Boolean = true) {
    ActivityPanel(stateLabel(state)) {
        Text(stringResource(R.string.activity_time_label), style = AscentTextStyles.SystemLabel, color = AscentColors.Mist)
        Text(elapsed, style = AscentTextStyles.NumberLarge, color = AscentColors.GlowCyan)
        if (state != "RESULT") {
            if (!singleFocus) Button(onClick = { control(if (state == "RUNNING") "PAUSE" else "RESUME") }, enabled = !busy && (state == "RUNNING" || canResume), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(if (state == "RUNNING") R.string.activity_pause else R.string.activity_resume))
            }
            OutlinedButton(onClick = { control("FINISH") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.activity_finish)) }
        }
        TextButton(onClick = { control("DISCARD") }, enabled = !busy) { Text(stringResource(R.string.activity_discard), color = AscentColors.Mist) }
    }
}

private fun duration(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

@Preview(name = "Task session — running")
@Composable
private fun RunningSessionPreview() { AscentTheme { Column { SessionPanel("RUNNING", "00:12:30", false, {}) } } }

@Preview(name = "Task session — paused")
@Composable
private fun PausedSessionPreview() { AscentTheme { Column { SessionPanel("PAUSED", "00:12:30", false, {}) } } }

@Preview(name = "Task measurement choices")
@Composable
private fun MeasurementPreview() { AscentTheme { ModePicker(MeasurementMode.COUNT, {}) } }

private object PreviewActivityActions : ActivityActions {
    override fun closePlanner() {}
    override fun closeTask() {}
    override fun openItem(id: Long) {}
    override fun savePlan(day: Long, items: List<PlannedTask>) {}
    override fun bind(item: Long, quest: Long) {}
    override fun mode(id: Long, mode: String) {}
    override fun start(manual: Boolean) {}
    override fun control(id: String, command: String) {}
    override fun saveResult(id: String, value: Double, adjusted: Boolean, note: String) {}
    override fun remove(id: String) {}
}

private fun sampleActivity(state: String, progress: Double = 25.0): ActivitiesUi {
    val day = LocalDate.of(2026, 11, 17).toEpochDay()
    val plan = DayPlanEntity(1, day, if (state == "EXPIRED") "EXPIRED" else "ACTIVE", 0, 0)
    val item = PlanItemEntity(1, 1, 0, "Push-ups", mode = "COUNT", target = 40.0, unit = "reps", progress = progress, done = progress >= 40)
    val session = TaskSessionEntity("sample", itemId = 1, state = state, startedAt = 0, endsAt = 86400000, activeSeconds = 750)
    return ActivitiesUi(data = ActivitySnapshot(day, listOf(plan), listOf(item), emptyList(), listOf(session), 0, 86400000), task = TaskSelection(itemId = 1), seconds = mapOf("sample" to 750L))
}

@Preview(name = "Task details and partial progress", widthDp = 400, heightDp = 800)
@Composable
private fun TaskDetailPreview() { AscentTheme { SystemBackground { TaskScreen(sampleActivity("SAVED"), PreviewActivityActions) } } }

@Preview(name = "Task result entry", widthDp = 400, heightDp = 800)
@Composable
private fun TaskResultPreview() { AscentTheme { SystemBackground { TaskScreen(sampleActivity("RESULT"), PreviewActivityActions) } } }

@Preview(name = "Task complete", widthDp = 400, heightDp = 800)
@Composable
private fun TaskCompletePreview() { AscentTheme { SystemBackground { TaskScreen(sampleActivity("SAVED", 40.0), PreviewActivityActions) } } }

@Preview(name = "Task expired", widthDp = 400, heightDp = 800)
@Composable
private fun TaskExpiredPreview() { AscentTheme { SystemBackground { TaskScreen(sampleActivity("EXPIRED"), PreviewActivityActions) } } }

@Preview(name = "Planner today", widthDp = 400, heightDp = 800)
@Composable
private fun PlannerPreview() { AscentTheme { SystemBackground { PlannerScreen(sampleActivity("SAVED"), PreviewActivityActions) } } }

@Preview(name = "Planner entry", widthDp = 400)
@Composable
private fun PlannerEntryPreview() { AscentTheme { SystemBackground { Box(Modifier.padding(16.dp)) { PlannerEntry {} } } } }

@Preview(name = "Planner tomorrow editor", widthDp = 400, heightDp = 900)
@Composable
private fun TomorrowPlannerPreview() { AscentTheme { SystemBackground { PlannerScreen(sampleActivity("SAVED"), PreviewActivityActions, initialTab = 1) } } }

@Preview(name = "Planner history", widthDp = 400, heightDp = 800)
@Composable
private fun PlannerHistoryPreview() { AscentTheme { SystemBackground { PlannerScreen(sampleActivity("EXPIRED"), PreviewActivityActions, initialTab = 2) } } }

@Preview(name = "Single-session daily focus", widthDp = 400)
@Composable
private fun SingleFocusPreview() { AscentTheme { SystemBackground { SessionPanel("RUNNING", "00:12:30", false, {}, singleFocus = true) } } }
