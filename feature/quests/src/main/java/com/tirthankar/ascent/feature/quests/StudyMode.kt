package com.tirthankar.ascent.feature.quests

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.db.StudySessionEntity
import com.tirthankar.ascent.data.repo.StudyRepository
import com.tirthankar.ascent.data.repo.StudySnapshot
import com.tirthankar.ascent.designsystem.*
import com.tirthankar.ascent.engine.StudyRules
import com.tirthankar.ascent.engine.TargetUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

data class StudyUi(val data: StudySnapshot? = null, val busy: Boolean = false, val message: Int? = null)

interface StudyActions {
    fun start(subject: String, objective: String, seconds: Long, questId: Long?)
    fun finish(id: Long, early: Boolean = false)
    fun discard(id: Long)
    fun submit(id: Long, learned: String, nextStep: String)
}

@HiltViewModel
class StudyViewModel @Inject constructor(private val repository: StudyRepository) : ViewModel(), StudyActions {
    private val mutable = MutableStateFlow(StudyUi())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    init {
        viewModelScope.launch {
            mutable.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
                if (visible) while (true) {
                    mutex.withLock { mutable.value = mutable.value.copy(data = repository.snapshot()) }
                    delay(1000)
                }
            }
        }
    }

    private fun action(block: suspend () -> Boolean) {
        viewModelScope.launch {
            mutex.withLock {
                mutable.value = mutable.value.copy(busy = true)
                try {
                    val ok = block()
                    mutable.value = mutable.value.copy(data = repository.snapshot(), message = if (ok) null else R.string.study_refused)
                } finally {
                    mutable.value = mutable.value.copy(busy = false)
                }
            }
        }
    }

    override fun start(subject: String, objective: String, seconds: Long, questId: Long?) = action { repository.start(subject, objective, seconds, questId) != null }
    override fun finish(id: Long, early: Boolean) = action { repository.finish(id, early) }
    override fun discard(id: Long) = action { repository.discard(id) }
    override fun submit(id: Long, learned: String, nextStep: String) = action { repository.submit(id, learned, nextStep) }
}

@Composable
fun StudyRoute(onBack: () -> Unit, viewModel: StudyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudyScreen(state, viewModel, onBack)
}

/** A labelled, full-width special action; the book sigil is decorative. */
@Composable
internal fun StudyEntry(onClick: () -> Unit) {
    HoloPanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick), accent = AscentColors.GlowCyan) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BookSigil()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.study_title), color = AscentColors.GlowCyan,
                    style = MaterialTheme.typography.headlineLarge.copy(shadow = Shadow(AscentColors.SystemBlue, blurRadius = 18f)))
                Text(stringResource(R.string.study_entry_hint), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist)
            }
            Text("›", style = MaterialTheme.typography.headlineLarge, color = AscentColors.GlowCyan, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun BookSigil() {
    val blue = AscentColors.SystemBlue
    val cyan = AscentColors.GlowCyan
    Canvas(Modifier.size(52.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawCircle(blue.copy(alpha = .3f), style = Stroke(stroke))
        for (direction in listOf(-1, 1)) {
            val mid = Offset(w * .5f, h * .3f)
            val edge = Offset(w * (.5f + direction * .32f), h * .22f)
            drawLine(cyan, mid, edge, stroke)
            drawLine(cyan, edge, edge.copy(y = h * .68f), stroke)
            drawLine(cyan, edge.copy(y = h * .68f), mid.copy(y = h * .78f), stroke)
            drawLine(blue, Offset(w * (.5f + direction * .1f), h * .46f), Offset(w * (.5f + direction * .24f), h * .42f), stroke)
            drawLine(blue, Offset(w * (.5f + direction * .1f), h * .59f), Offset(w * (.5f + direction * .24f), h * .55f), stroke)
        }
        drawLine(cyan, Offset(w * .5f, h * .3f), Offset(w * .5f, h * .78f), stroke)
    }
}

@Composable
private fun StudyScreen(state: StudyUi, actions: StudyActions, onBack: () -> Unit) {
    val data = state.data
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmId by rememberSaveable { mutableLongStateOf(0) }
    ActivityPage(stringResource(R.string.study_title), onBack) {
        if (data == null) { CircularProgressIndicator(); return@ActivityPage }
        val active = data.active
        val latest = data.sessions.firstOrNull()
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.study_protocol), accent = AscentColors.GlowCyan) {
            Text(stringResource(R.string.study_allowance, data.attempts, StudyRules.MAX_ATTEMPTS), style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.study_rules), color = AscentColors.Mist)
        }
        if (active == null) {
            if (latest != null) StudyOutcome(latest)
            StudyBriefing(data, state.busy, actions)
        } else {
            ActivityPanel(stringResource(R.string.study_mission)) {
                Text(active.subject, style = MaterialTheme.typography.headlineLarge, color = AscentColors.GlowCyan)
                Text(active.objective, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
                StudyRing(data.elapsed, active.plannedSeconds)
                val quotes = stringArrayResource(R.array.study_quotes)
                Text(quotes[active.quoteIndex.coerceIn(quotes.indices)], style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center, color = AscentColors.GlowCyan, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.study_leave_hint), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist)
            }
            if (active.state == "RUNNING") {
                Button(onClick = {
                    if (data.elapsed < active.plannedSeconds) { confirmation = "EARLY"; confirmId = active.id }
                    else actions.finish(active.id)
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                    Text(stringResource(if (data.elapsed < active.plannedSeconds) R.string.study_finish_early else R.string.activity_finish), style = MaterialTheme.typography.titleLarge)
                }
            } else StudyDebrief(active, state.busy, actions)
            OutlinedButton(onClick = { confirmation = "DISCARD"; confirmId = active.id }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.study_discard)) }
        }
        state.message?.let { Text(stringResource(it), color = AscentColors.PenaltyRed) }
        SystemHeader(stringResource(R.string.study_history))
        if (data.sessions.isEmpty()) Text(stringResource(R.string.study_history_empty), color = AscentColors.Mist)
        var showAll by rememberSaveable { mutableStateOf(false) }
        (if (showAll) data.sessions else data.sessions.take(10)).forEach { session -> StudyHistoryCard(session) }
        if (!showAll && data.sessions.size > 10) TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.study_all_history)) }
    }
    if (confirmation != null) AlertDialog(
        onDismissRequest = { confirmation = null },
        title = { Text(stringResource(if (confirmation == "DISCARD") R.string.study_discard_title else R.string.study_early_title)) },
        text = { Text(stringResource(R.string.study_zero_confirmation)) },
        confirmButton = { TextButton(onClick = {
            if (confirmation == "DISCARD") actions.discard(confirmId) else actions.finish(confirmId, early = true)
            confirmation = null
        }, enabled = !state.busy) { Text(stringResource(if (confirmation == "DISCARD") R.string.study_discard else R.string.study_finish_early)) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.study_keep_studying)) } },
    )
}

@Composable
private fun StudyBriefing(data: StudySnapshot, busy: Boolean, actions: StudyActions) {
    var subject by rememberSaveable { mutableStateOf("") }
    var objective by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("30") }
    var questId by rememberSaveable { mutableStateOf<Long?>(null) }
    val linked = data.quests.find { it.id == questId }
    val targetMinutes = linked?.let { it.target * when (it.unit) { TargetUnit.HOURS -> 60.0; TargetUnit.SECONDS -> 1.0 / 60; else -> 1.0 } } ?: 0.0
    val duration = minutes.toLongOrNull()
    val validDuration = duration != null && duration >= 30 && duration <= 24 * 60 && duration >= targetMinutes
    ActivityPanel(stringResource(R.string.study_mission)) {
        OutlinedTextField(subject, { subject = it.take(120) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.study_subject)) })
        OutlinedTextField(objective, { objective = it.take(500) }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(R.string.study_objective)) })
        OutlinedTextField(minutes, { minutes = it }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(stringResource(R.string.study_minutes)) }, isError = !validDuration,
            supportingText = { Text(stringResource(R.string.study_duration_hint)) })
        Text(stringResource(R.string.study_link_label), style = AscentTextStyles.SystemLabel, color = AscentColors.GlowCyan)
        FilterChip(selected = questId == null, onClick = { questId = null }, label = { Text(stringResource(R.string.study_unlinked)) })
        data.quests.forEach { quest ->
            FilterChip(selected = questId == quest.id, onClick = {
                questId = quest.id
                val required = kotlin.math.ceil(quest.target * when (quest.unit) { TargetUnit.HOURS -> 60.0; TargetUnit.SECONDS -> 1.0 / 60; else -> 1.0 }).toLong().coerceAtLeast(30)
                if ((minutes.toLongOrNull() ?: 0) < required) minutes = required.toString()
            }, label = { Text(quest.title) })
        }
        if (questId != null && linked == null) Text(stringResource(R.string.study_link_gone), color = AscentColors.PenaltyRed)
        if (data.timerBlocked) Text(stringResource(R.string.study_other_timer), color = AscentColors.GlowCyan)
        if (data.attempts >= StudyRules.MAX_ATTEMPTS) Text(stringResource(R.string.study_limit), color = AscentColors.GlowCyan)
        Button(onClick = { actions.start(subject, objective, duration!! * 60, questId) },
            enabled = !busy && !data.timerBlocked && data.attempts < StudyRules.MAX_ATTEMPTS && subject.isNotBlank() && objective.isNotBlank() && validDuration && (questId == null || linked != null),
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
            Text(stringResource(R.string.study_start), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StudyRing(seconds: Long, target: Long) {
    val fraction by animateFloatAsState((seconds.toFloat() / target.coerceAtLeast(1)).coerceIn(0f, 1f),
        animationSpec = if (LocalReducedMotion.current) snap() else tween(800), label = "Study progress")
    val cyan = AscentColors.GlowCyan
    val blue = AscentColors.SystemBlue
    val elapsed = studyDuration(seconds)
    val description = stringResource(R.string.study_ring_description, elapsed, studyDuration(target))
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ringSize = minOf(maxWidth, 300.dp)
        Box(Modifier.size(ringSize).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = 18.dp.toPx()
                val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                drawCircle(blue.copy(alpha = .15f), radius = size.width / 2 - inset, style = Stroke(18.dp.toPx()))
                drawArc(blue.copy(alpha = .25f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(2.dp.toPx()))
                drawArc(cyan, -90f, 360 * fraction, false, Offset(inset, inset), arcSize, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(blue.copy(alpha = .35f), radius = size.width / 2 - 31.dp.toPx(), style = Stroke(1.dp.toPx()))
                for (i in 0 until 24) {
                    val angle = Math.PI * i / 12
                    val radius = size.width / 2 - 5.dp.toPx()
                    val center = Offset(size.width / 2, size.height / 2)
                    drawLine(if (i % 2 == 0) cyan.copy(alpha = .6f) else blue.copy(alpha = .35f),
                        center + Offset(cos(angle).toFloat() * (radius - 5.dp.toPx()), sin(angle).toFloat() * (radius - 5.dp.toPx())),
                        center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius), 1.dp.toPx())
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.study_focus_label), style = AscentTextStyles.SystemLabel, color = cyan)
                val timerSize = minOf(36f, (ringSize.value - 72) / (elapsed.length * .65f * fontScale)).coerceAtLeast(12f)
                Text(elapsed, style = AscentTextStyles.NumberLarge.copy(fontSize = timerSize.sp), color = AscentColors.Mist)
                Text(stringResource(R.string.study_target_time, studyDuration(target)), style = AscentTextStyles.Number, color = cyan)
            }
        }
    }
}

@Composable
private fun StudyDebrief(session: StudySessionEntity, busy: Boolean, actions: StudyActions) {
    var learned by rememberSaveable(session.id) { mutableStateOf("") }
    var nextStep by rememberSaveable(session.id) { mutableStateOf("") }
    ActivityPanel(stringResource(R.string.study_debrief)) {
        Text(stringResource(R.string.study_ready_reward, StudyRules.reward(session.activeSeconds, session.plannedSeconds)), color = AscentColors.GlowCyan, style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(learned, { learned = it.take(2000) }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(R.string.study_learned)) })
        OutlinedTextField(nextStep, { nextStep = it.take(1000) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.study_next_step)) })
        Button(onClick = { actions.submit(session.id, learned, nextStep) }, enabled = !busy && learned.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.study_save))
        }
    }
}

@Composable
private fun StudyOutcome(session: StudySessionEntity) {
    ActivityPanel(studyState(session.state)) {
        Text(session.subject, style = MaterialTheme.typography.headlineMedium, color = AscentColors.GlowCyan)
        Text(stringResource(R.string.study_rewards, session.xpAwarded, session.questXpAwarded), style = AscentTextStyles.Number, color = AscentColors.Mist)
        if (session.nextStep.isNotBlank()) Text(stringResource(R.string.study_next_mission, session.nextStep), color = AscentColors.GlowCyan)
    }
}

@Composable
private fun StudyHistoryCard(session: StudySessionEntity) {
    ActivityPanel(studyState(session.state)) {
        Text(session.subject, style = MaterialTheme.typography.titleLarge, color = AscentColors.GlowCyan)
        Text(session.objective, color = AscentColors.Mist)
        Text(LocalDate.ofEpochDay(session.day).toString(), style = AscentTextStyles.Number)
        Text(stringResource(R.string.study_history_duration, studyDuration(session.activeSeconds), studyDuration(session.plannedSeconds)))
        Text(stringResource(R.string.study_rewards, session.xpAwarded, session.questXpAwarded), style = AscentTextStyles.Number)
        if (session.learned.isNotBlank()) Text(session.learned)
        if (session.nextStep.isNotBlank()) Text(stringResource(R.string.study_next_mission, session.nextStep), color = AscentColors.GlowCyan)
    }
}

@Composable
private fun studyState(state: String): String = stringResource(when (state) {
    "RUNNING" -> R.string.study_running
    "RESULT" -> R.string.study_debrief
    "COMPLETED" -> R.string.study_completed
    "DISCARDED" -> R.string.study_discarded
    "INTERRUPTED" -> R.string.study_interrupted
    else -> R.string.study_incomplete
})

private fun studyDuration(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

private object PreviewStudyActions : StudyActions {
    override fun start(subject: String, objective: String, seconds: Long, questId: Long?) {}
    override fun finish(id: Long, early: Boolean) {}
    override fun discard(id: Long) {}
    override fun submit(id: Long, learned: String, nextStep: String) {}
}

private fun previewStudy(state: String? = null): StudyUi {
    val day = LocalDate.of(2026, 11, 17).toEpochDay()
    val session = state?.let { StudySessionEntity(1, day, "Mathematics", "Solve five integration problems", 1800, 3,
        state = it, startedAt = 0, endsAt = 86400000, activeSeconds = if (it == "RUNNING") 750 else 1800, boot = 1,
        learned = if (it == "COMPLETED") "Recognized when substitution simplifies an integral." else "", xpAwarded = if (it == "COMPLETED") 30 else 0) }
    return StudyUi(StudySnapshot(day, if (session == null) 0 else 1, listOfNotNull(session), session?.takeIf { it.state in listOf("RUNNING", "RESULT") }, emptyList(), session?.activeSeconds ?: 0, false))
}

@Preview(name = "Study Mode entry", widthDp = 400)
@Composable
private fun StudyEntryPreview() { AscentTheme { SystemBackground { Box(Modifier.padding(16.dp)) { StudyEntry {} } } } }

@Preview(name = "Study Mode briefing", widthDp = 400, heightDp = 1000)
@Composable
private fun StudyBriefingPreview() { AscentTheme { SystemBackground { StudyScreen(previewStudy(), PreviewStudyActions, {}) } } }

@Preview(name = "Study Mode running", widthDp = 400, heightDp = 1000)
@Composable
private fun StudyRunningPreview() { AscentTheme { SystemBackground { StudyScreen(previewStudy("RUNNING"), PreviewStudyActions, {}) } } }

@Preview(name = "Study Mode debrief", widthDp = 400, heightDp = 1100)
@Composable
private fun StudyDebriefPreview() { AscentTheme { SystemBackground { StudyScreen(previewStudy("RESULT"), PreviewStudyActions, {}) } } }

@Preview(name = "Study Mode completed", widthDp = 400, heightDp = 1000)
@Composable
private fun StudyCompletedPreview() { AscentTheme { SystemBackground { StudyScreen(previewStudy("COMPLETED"), PreviewStudyActions, {}) } } }
