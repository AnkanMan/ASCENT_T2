package com.tirthankar.ascent.feature.status

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.SafetyRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import javax.inject.Inject

/** The Settings form (Task 1). [saved] flashes after a save. */
data class SettingsUiState(
    val settings: AscentSettings = AscentSettings(),
    val saved: Boolean = false,
    val loaded: Boolean = false,
) {
    val valid: Boolean get() = SafetyRules.hasEnoughRestDays(settings.restDays)
}

enum class TimeField { RESET, WAKE, QUIET_START, QUIET_END }

sealed interface SettingsEvent {
    data class Shift(val field: TimeField, val minutes: Long) : SettingsEvent
    data class ToggleRestDay(val day: DayOfWeek) : SettingsEvent
    data class Dossier(val text: String) : SettingsEvent
    data class Sound(val on: Boolean) : SettingsEvent
    data class Haptics(val on: Boolean) : SettingsEvent
    data object Save : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = SettingsUiState(repository.settings.first(), loaded = true) }
    }

    fun onEvent(event: SettingsEvent) {
        _state.update { state ->
            val s = state.settings
            val next = when (event) {
                is SettingsEvent.Shift -> when (event.field) {
                    TimeField.RESET -> s.copy(resetTime = s.resetTime.plusMinutes(event.minutes))
                    TimeField.WAKE -> s.copy(wakeTime = s.wakeTime.plusMinutes(event.minutes))
                    TimeField.QUIET_START -> s.copy(quietStart = s.quietStart.plusMinutes(event.minutes))
                    TimeField.QUIET_END -> s.copy(quietEnd = s.quietEnd.plusMinutes(event.minutes))
                }
                is SettingsEvent.ToggleRestDay -> s.copy(restDays = if (event.day in s.restDays) s.restDays - event.day else s.restDays + event.day)
                is SettingsEvent.Dossier -> s.copy(dossierNotes = event.text)
                is SettingsEvent.Sound -> s.copy(soundOn = event.on)
                is SettingsEvent.Haptics -> s.copy(hapticsOn = event.on)
                SettingsEvent.Save -> s
            }
            state.copy(settings = next, saved = false)
        }
        if (event == SettingsEvent.Save) save()
    }

    private fun save() = viewModelScope.launch {
        val s = _state.value.settings
        if (!SafetyRules.hasEnoughRestDays(s.restDays)) return@launch
        // A new reset time reschedules the workers (AscentApplication watches it).
        repository.update { s }
        _state.update { it.copy(saved = true) }
    }
}

/** [extra] is shown at the bottom: the app puts the debug panel's entry there in debug builds. */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.loaded) return
    Materialize(modifier) { SettingsScreen(state, viewModel::onEvent, onBack, backup = { BackupRoute() }, extra = extra) }
}

/**
 * Settings: rhythm, rest days, Dossier, sound and haptics, then [backup] (Phase 5; it
 * saves on its own, apart from the Save button). Stateless.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backup: @Composable () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    val s = state.settings
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("← " + stringResource(R.string.settings_back), color = AscentColors.GlowCyan)
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.settings_rhythm)) {
            TimeRow(R.string.settings_reset, s.resetTime) { onEvent(SettingsEvent.Shift(TimeField.RESET, it)) }
            TimeRow(R.string.settings_wake, s.wakeTime) { onEvent(SettingsEvent.Shift(TimeField.WAKE, it)) }
            TimeRow(R.string.settings_quiet_start, s.quietStart) { onEvent(SettingsEvent.Shift(TimeField.QUIET_START, it)) }
            TimeRow(R.string.settings_quiet_end, s.quietEnd) { onEvent(SettingsEvent.Shift(TimeField.QUIET_END, it)) }
            Text(stringResource(R.string.settings_rest_days), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in s.restDays,
                        onClick = { onEvent(SettingsEvent.ToggleRestDay(day)) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0])) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AscentColors.SystemBlue.copy(alpha = 0.3f), labelColor = AscentColors.Mist, selectedLabelColor = AscentColors.Mist),
                    )
                }
            }
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.settings_dossier)) {
            OutlinedTextField(
                value = s.dossierNotes,
                onValueChange = { onEvent(SettingsEvent.Dossier(it)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                supportingText = { Text(stringResource(R.string.settings_dossier_hint)) },
            )
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.settings_feedback)) {
            SwitchRow(R.string.settings_sound, s.soundOn) { onEvent(SettingsEvent.Sound(it)) }
            SwitchRow(R.string.settings_haptics, s.hapticsOn) { onEvent(SettingsEvent.Haptics(it)) }
            Text(stringResource(R.string.settings_sound_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onEvent(SettingsEvent.Save) }, enabled = state.valid, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_save), color = AscentColors.LegendGold)
            }
            if (state.saved) {
                Text(stringResource(R.string.settings_saved), color = AscentColors.GlowCyan, modifier = Modifier.padding(start = 12.dp))
            }
        }
        backup()
        extra()
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun TimeRow(label: Int, time: LocalTime, onShift: (Long) -> Unit) {
    val name = stringResource(label)
    val text = time.format(TIME)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
        val earlier = stringResource(R.string.settings_earlier, name)
        IconButton(onClick = { onShift(-15) }, modifier = Modifier.semantics { contentDescription = earlier }) {
            Text("−", style = AscentTextStyles.NumberLarge, color = AscentColors.GlowCyan, modifier = Modifier.clearAndSetSemantics { })
        }
        Text(text, style = AscentTextStyles.Number, color = AscentColors.Mist)
        val later = stringResource(R.string.settings_later, name)
        IconButton(onClick = { onShift(15) }, modifier = Modifier.semantics { contentDescription = later }) {
            Text("+", style = AscentTextStyles.NumberLarge, color = AscentColors.GlowCyan, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Preview(name = "Settings", widthDp = 400, heightDp = 1900)
@Composable
private fun SettingsPreview() {
    AscentTheme {
        SystemBackground {
            SettingsScreen(
                SettingsUiState(AscentSettings(dossierNotes = "BMFT draft due in March."), loaded = true, saved = true),
                {},
                {},
                backup = { BackupSection(SampleBackup.working, {}, onPickFolder = {}, onPickRestore = {}) },
            )
        }
    }
}
