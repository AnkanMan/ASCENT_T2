package com.tirthankar.ascent.feature.skills

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.repo.FocusSession
import com.tirthankar.ascent.data.repo.PowerEffects
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.LocalReducedMotion
import com.tirthankar.ascent.engine.HyperfocusRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

/** The Focus Session overlay (A6). Null [session] means nothing is shown. */
data class FocusUiState(
    val session: FocusSession? = null,
    val elapsedSeconds: Long = 0,
    val totalSeconds: Long = HyperfocusRules.SESSION_MINUTES * 60L,
    val confirmEnd: Boolean = false,
    /** Ask for Do Not Disturb access once, at the first session. */
    val askDnd: Boolean = false,
) {
    val complete: Boolean get() = elapsedSeconds >= totalSeconds
    val remainingSeconds: Long get() = (totalSeconds - elapsedSeconds).coerceAtLeast(0)
    val fraction: Float get() = (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
}

sealed interface FocusEvent {
    data object EndEarly : FocusEvent
    data object ConfirmEnd : FocusEvent
    data object CancelEnd : FocusEvent
    data object Finish : FocusEvent
    data object DndHandled : FocusEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FocusSessionViewModel @Inject constructor(
    private val effects: PowerEffects,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    val dnd: FocusDnd,
) : ViewModel() {

    private data class Local(val confirmEnd: Boolean = false, val askDnd: Boolean = false)

    private val local = MutableStateFlow(Local())

    private val ticking = effects.focusSession.flatMapLatest { session ->
        if (session == null) {
            flowOf<Pair<FocusSession?, Long>>(null to 0L)
        } else {
            flow<Pair<FocusSession?, Long>> {
                while (true) {
                    emit(session to Duration.between(session.startedAt, time.now()).seconds.coerceAtLeast(0))
                    delay(1_000)
                }
            }
        }
    }

    val uiState: StateFlow<FocusUiState> = combine(ticking, local) { (session, elapsed), l ->
        FocusUiState(session = session, elapsedSeconds = elapsed, confirmEnd = l.confirmEnd, askDnd = l.askDnd && session != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    init {
        // Do Not Disturb follows the session: on while it runs (if allowed), restored after.
        effects.focusSession.distinctUntilChangedBy { it?.castId }
            .onEach { session ->
                if (session == null) {
                    dnd.off()
                } else if (dnd.hasAccess()) {
                    dnd.on()
                } else if (!settings.focusDndAsked.first()) {
                    local.update { it.copy(askDnd = true) }
                }
            }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: FocusEvent) {
        when (event) {
            FocusEvent.EndEarly -> local.update { it.copy(confirmEnd = true) }
            FocusEvent.CancelEnd -> local.update { it.copy(confirmEnd = false) }
            FocusEvent.ConfirmEnd, FocusEvent.Finish -> viewModelScope.launch {
                local.update { it.copy(confirmEnd = false) }
                uiState.value.session?.let { effects.endFocus(it.castId) }
            }
            FocusEvent.DndHandled -> viewModelScope.launch {
                settings.setFocusDndAsked()
                local.update { it.copy(askDnd = false) }
                if (dnd.hasAccess()) dnd.on()
            }
        }
    }
}

/** Shows the Focus Session over everything while one runs. Place once in the app shell. */
@Composable
fun FocusSessionHost(viewModel: FocusSessionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.session == null) return
    val context = LocalContext.current
    FocusSessionScreen(state, viewModel::onEvent)
    if (state.askDnd) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(FocusEvent.DndHandled) },
            title = { Text(stringResource(R.string.focus_dnd_title)) },
            text = { Text(stringResource(R.string.focus_dnd_text)) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(viewModel.dnd.accessSettings())
                    viewModel.onEvent(FocusEvent.DndHandled)
                }) { Text(stringResource(R.string.focus_dnd_allow), color = AscentColors.LegendGold) }
            },
            dismissButton = { TextButton(onClick = { viewModel.onEvent(FocusEvent.DndHandled) }) { Text(stringResource(R.string.focus_dnd_later)) } },
            containerColor = AscentColors.PanelSolid,
        )
    }
}

/** The full-screen dark timer with a pulsing ring and the linked quest. Stateless. */
@Composable
fun FocusSessionScreen(state: FocusUiState, onEvent: (FocusEvent) -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val pulse = if (reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "focus")
        val value by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "pulse")
        value
    }
    val remaining = stringResource(R.string.focus_remaining_description, state.remainingSeconds / 60)
    Box(
        modifier
            .fillMaxSize()
            .background(AscentColors.Void)
            // Touches don't reach the app beneath: the session is locked.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(24.dp)) {
            Text(stringResource(R.string.focus_header), style = AscentTextStyles.SystemHeader, color = AscentColors.SystemBlue)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                Canvas(Modifier.size(260.dp)) {
                    val r = size.minDimension / 2 * 0.9f
                    val topLeft = Offset(center.x - r, center.y - r)
                    drawCircle(AscentColors.SystemBlue.copy(alpha = 0.15f * pulse), r + 10.dp.toPx() * pulse, center)
                    drawCircle(AscentColors.SystemBlue.copy(alpha = 0.3f), r, center, style = Stroke(2.dp.toPx()))
                    drawArc(
                        AscentColors.GlowCyan.copy(alpha = 0.6f + 0.4f * pulse),
                        -90f,
                        360f * state.fraction,
                        false,
                        topLeft,
                        Size(2 * r, 2 * r),
                        style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Text(
                    if (state.complete) stringResource(R.string.focus_done_short) else clock(state.remainingSeconds),
                    style = AscentTextStyles.NumberLarge.copy(fontSize = 48.sp),
                    color = if (state.complete) AscentColors.LegendGold else AscentColors.Mist,
                    modifier = Modifier.semantics { contentDescription = remaining },
                )
            }
            Text(
                state.session?.questTitle.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = AscentColors.Mist,
                textAlign = TextAlign.Center,
            )
            if (state.complete) {
                Text(
                    stringResource(R.string.focus_complete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AscentColors.GlowCyan,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedButton(onClick = { onEvent(FocusEvent.Finish) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.focus_claim), color = AscentColors.LegendGold)
                }
            } else {
                OutlinedButton(onClick = { onEvent(FocusEvent.EndEarly) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.focus_end_early), color = AscentColors.Mist)
                }
            }
        }
    }
    if (state.confirmEnd) {
        AlertDialog(
            onDismissRequest = { onEvent(FocusEvent.CancelEnd) },
            title = { Text(stringResource(R.string.focus_end_title)) },
            text = { Text(stringResource(R.string.focus_end_text)) },
            confirmButton = {
                TextButton(onClick = { onEvent(FocusEvent.ConfirmEnd) }) { Text(stringResource(R.string.focus_end_confirm), color = AscentColors.PenaltyRed) }
            },
            dismissButton = { TextButton(onClick = { onEvent(FocusEvent.CancelEnd) }) { Text(stringResource(R.string.focus_keep_going)) } },
            containerColor = AscentColors.PanelSolid,
        )
    }
}

private fun clock(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

@Preview(name = "Focus Session", widthDp = 400, heightDp = 800)
@Composable
private fun FocusSessionPreview() {
    AscentTheme {
        FocusSessionScreen(
            FocusUiState(
                session = FocusSession(1, 2, "Deep work: derive the KPZ scaling relation", java.time.Instant.EPOCH),
                elapsedSeconds = 37 * 60 + 12,
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Focus Session — complete", widthDp = 400, heightDp = 800)
@Composable
private fun FocusSessionDonePreview() {
    AscentTheme {
        FocusSessionScreen(
            FocusUiState(
                session = FocusSession(1, 2, "Deep work: derive the KPZ scaling relation", java.time.Instant.EPOCH),
                elapsedSeconds = 90 * 60L,
            ),
            onEvent = {},
        )
    }
}
