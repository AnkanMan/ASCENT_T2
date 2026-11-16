package com.tirthankar.ascent.feature.awakening

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.LocalReducedMotion
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.designsystem.SystemHeader
import com.tirthankar.ascent.engine.Stat
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** [restore] shows under Begin on the first screen: the app puts "Restore from a backup" there (Phase 5). */
@Composable
fun AwakeningRoute(
    modifier: Modifier = Modifier,
    restore: @Composable () -> Unit = {},
    viewModel: AwakeningViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AwakeningScreen(state, viewModel::onEvent, modifier, restore)
}

/** The first-run sequence (SPEC "Character creation"). Stateless. */
@Composable
fun AwakeningScreen(
    state: AwakeningUiState,
    onEvent: (AwakeningEvent) -> Unit,
    modifier: Modifier = Modifier,
    restore: @Composable () -> Unit = {},
) {
    if (state.step == AwakeningStep.DETECTION) {
        Detection(onBegin = { onEvent(AwakeningEvent.Next) }, modifier = modifier, restore = restore)
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Materialize {
            when (state.step) {
                AwakeningStep.IDENTITY -> Identity(state, onEvent)
                AwakeningStep.MEASUREMENT -> MeasurementStep(state, onEvent)
                AwakeningStep.RHYTHM -> Rhythm(state, onEvent)
                AwakeningStep.CONFIRM -> Confirm(state)
                AwakeningStep.DETECTION -> Unit
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = { onEvent(AwakeningEvent.Back) },
                enabled = !state.saving,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.awakening_back), color = AscentColors.Mist) }
            OutlinedButton(
                onClick = { onEvent(AwakeningEvent.Next) },
                enabled = state.canContinue,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                val label = when {
                    state.saving -> R.string.awakening_saving
                    state.step == AwakeningStep.CONFIRM -> R.string.awakening_finish
                    else -> R.string.awakening_next
                }
                Text(stringResource(label), color = AscentColors.GlowCyan)
            }
        }
    }
}

/** Black screen, a blinking cursor, then the System's first line, typed out. */
@Composable
private fun Detection(onBegin: () -> Unit, modifier: Modifier = Modifier, restore: @Composable () -> Unit = {}) {
    val full = "◆ " + stringResource(R.string.awakening_detection).uppercase()
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(if (reduced) full.length else 0) }
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(reduced) {
        if (!reduced) {
            delay(900)
            while (shown < full.length) {
                shown++
                delay(35)
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            cursor = !cursor
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = full.take(shown) + if (cursor || reduced) "▌" else " ",
            style = AscentTextStyles.SystemHeader.copy(fontSize = AscentTextStyles.SystemHeader.fontSize * 1.3f),
            color = AscentColors.SystemBlue,
            modifier = Modifier.clearAndSetSemantics { contentDescription = full },
        )
        if (shown >= full.length) {
            Spacer(Modifier.heightIn(min = 32.dp))
            Materialize {
                Column {
                    OutlinedButton(onClick = onBegin, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.awakening_begin), color = AscentColors.GlowCyan)
                    }
                    restore()
                }
            }
        }
    }
}

@Composable
private fun Identity(state: AwakeningUiState, onEvent: (AwakeningEvent) -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.awakening_identity_header)) {
        Text(stringResource(R.string.awakening_identity_prompt), style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(AwakeningEvent.NameChanged(it)) },
            label = { Text(stringResource(R.string.awakening_name_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Text(
            stringResource(R.string.awakening_title_line),
            style = MaterialTheme.typography.labelLarge,
            color = AscentColors.GlowCyan,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeasurementStep(state: AwakeningUiState, onEvent: (AwakeningEvent) -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.awakening_measure_header)) {
        Text(stringResource(R.string.awakening_measure_prompt), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
        Spacer(Modifier.heightIn(min = 8.dp))
        MeasureRow(MeasureField.PUSH_UPS, R.string.measure_push_ups, state, onEvent)
        MeasureRow(MeasureField.SQUATS, R.string.measure_squats, state, onEvent)
        MeasureRow(MeasureField.PLANK, R.string.measure_plank, state, onEvent)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilterChip(
                selected = state.agilityMode == AgilityMode.STEPS,
                onClick = { onEvent(AwakeningEvent.AgilityModeChanged(AgilityMode.STEPS)) },
                label = { Text(stringResource(R.string.awakening_agility_steps)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = state.agilityMode == AgilityMode.RUN_1K,
                onClick = { onEvent(AwakeningEvent.AgilityModeChanged(AgilityMode.RUN_1K)) },
                label = { Text(stringResource(R.string.awakening_agility_run)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        if (state.agilityMode == AgilityMode.STEPS) {
            MeasureRow(MeasureField.STEPS, R.string.measure_steps, state, onEvent)
        } else {
            MeasureRow(MeasureField.RUN_1K, R.string.measure_run, state, onEvent)
        }
        MeasureRow(MeasureField.DEEP_WORK, R.string.measure_deep_work, state, onEvent)
        MeasureRow(MeasureField.PAGES, R.string.measure_pages, state, onEvent)
        MeasureRow(MeasureField.SLEEP, R.string.measure_sleep, state, onEvent)
    }
}

@Composable
private fun MeasureRow(
    field: MeasureField,
    @StringRes label: Int,
    state: AwakeningUiState,
    onEvent: (AwakeningEvent) -> Unit,
) {
    val input = state.fields[field] ?: FieldInput()
    val invalid = field in state.invalidFields
    val title = stringResource(label)
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = AscentColors.SystemBlue)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = input.current,
                label = stringResource(R.string.awakening_now),
                description = "$title, ${stringResource(R.string.awakening_now)}",
                isError = invalid,
                onChange = { onEvent(AwakeningEvent.CurrentChanged(field, it)) },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = input.goal,
                label = stringResource(R.string.awakening_goal),
                description = "$title, ${stringResource(R.string.awakening_goal)}",
                isError = invalid,
                onChange = { onEvent(AwakeningEvent.GoalChanged(field, it)) },
                modifier = Modifier.weight(1f),
            )
        }
        if (invalid) {
            Text(stringResource(R.string.awakening_invalid_number), style = MaterialTheme.typography.bodySmall, color = AscentColors.PenaltyRed)
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    description: String,
    isError: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        textStyle = AscentTextStyles.Number,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier.semantics { contentDescription = description },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Rhythm(state: AwakeningUiState, onEvent: (AwakeningEvent) -> Unit) {
    val s = state.settings
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.awakening_rhythm_header)) {
        TimeStepper(stringResource(R.string.awakening_reset_time), s.resetTime) { onEvent(AwakeningEvent.TimeStep(TimeSetting.RESET, it)) }
        TimeStepper(stringResource(R.string.awakening_wake_time), s.wakeTime) { onEvent(AwakeningEvent.TimeStep(TimeSetting.WAKE, it)) }
        Text(
            stringResource(R.string.awakening_lights_out_note),
            style = MaterialTheme.typography.bodySmall,
            color = AscentColors.Mist.copy(alpha = 0.7f),
        )
        TimeStepper(stringResource(R.string.awakening_quiet_start), s.quietStart) { onEvent(AwakeningEvent.TimeStep(TimeSetting.QUIET_START, it)) }
        TimeStepper(stringResource(R.string.awakening_quiet_end), s.quietEnd) { onEvent(AwakeningEvent.TimeStep(TimeSetting.QUIET_END, it)) }

        Text(
            stringResource(R.string.awakening_rest_days),
            style = MaterialTheme.typography.labelLarge,
            color = AscentColors.SystemBlue,
            modifier = Modifier.padding(top = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WEEK.forEach { day ->
                FilterChip(
                    selected = day in s.restDays,
                    onClick = { onEvent(AwakeningEvent.ToggleRestDay(day)) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0])) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (!state.restDaysValid) {
            Text(stringResource(R.string.awakening_rest_days_needed), style = MaterialTheme.typography.bodySmall, color = AscentColors.PenaltyRed)
        }
    }
}

private val WEEK = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** A time in 30-minute steps: − time +. */
@Composable
private fun TimeStepper(label: String, time: LocalTime, onStep: (Long) -> Unit) {
    val text = time.format(TIME)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
        val earlier = stringResource(R.string.awakening_earlier, time.minusMinutes(30).format(TIME))
        val later = stringResource(R.string.awakening_later, time.plusMinutes(30).format(TIME))
        IconButton(onClick = { onStep(-30) }, modifier = Modifier.semantics { contentDescription = "$label. $earlier" }) {
            Text("−", style = AscentTextStyles.NumberLarge.copy(fontSize = AscentTextStyles.Number.fontSize * 1.4f), color = AscentColors.GlowCyan)
        }
        Text(text, style = AscentTextStyles.Number, color = AscentColors.Mist, modifier = Modifier.width(56.dp))
        IconButton(onClick = { onStep(30) }, modifier = Modifier.semantics { contentDescription = "$label. $later" }) {
            Text("+", style = AscentTextStyles.NumberLarge.copy(fontSize = AscentTextStyles.Number.fontSize * 1.4f), color = AscentColors.GlowCyan)
        }
    }
}

@Composable
private fun Confirm(state: AwakeningUiState) {
    val stats = state.startingStats
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.awakening_confirm_header)) {
        Text(stringResource(R.string.awakening_confirm_prompt), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
        Spacer(Modifier.heightIn(min = 8.dp))
        Stat.entries.forEach { stat ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stat.name, style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue, modifier = Modifier.weight(1f))
                Text(stats[stat].toString(), style = AscentTextStyles.Number, color = AscentColors.Mist)
            }
        }
    }
}

// ---- Previews ----

/** Reduced motion so previews show the settled frame, not the first frame of Materialize. */
@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    AscentTheme {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            SystemBackground { content() }
        }
    }
}

@Preview(name = "Awakening — detection", widthDp = 400, heightDp = 800)
@Composable
private fun DetectionPreview() = PreviewFrame {
    AwakeningScreen(AwakeningUiState(), onEvent = {})
}

@Preview(name = "Awakening — identity", widthDp = 400, heightDp = 800)
@Composable
private fun IdentityPreview() = PreviewFrame {
    AwakeningScreen(AwakeningUiState(step = AwakeningStep.IDENTITY, name = "Player"), onEvent = {})
}

@Preview(name = "Awakening — measurement", widthDp = 400, heightDp = 1400)
@Composable
private fun MeasurementPreview() = PreviewFrame {
    AwakeningScreen(
        AwakeningUiState(
            step = AwakeningStep.MEASUREMENT,
            fields = AwakeningUiState.DEFAULT_FIELDS + (MeasureField.PUSH_UPS to FieldInput("15", "30")) +
                (MeasureField.SQUATS to FieldInput("abc", "40")),
        ),
        onEvent = {},
    )
}

@Preview(name = "Awakening — rhythm", widthDp = 400, heightDp = 900)
@Composable
private fun RhythmPreview() = PreviewFrame {
    AwakeningScreen(AwakeningUiState(step = AwakeningStep.RHYTHM), onEvent = {})
}

@Preview(name = "Awakening — confirm", widthDp = 400, heightDp = 800)
@Composable
private fun ConfirmPreview() = PreviewFrame {
    AwakeningScreen(
        AwakeningUiState(
            step = AwakeningStep.CONFIRM,
            fields = AwakeningUiState.DEFAULT_FIELDS + (MeasureField.PUSH_UPS to FieldInput("15", "30")) +
                (MeasureField.SLEEP to FieldInput("7", "7.5")),
        ),
        onEvent = {},
    )
}
