package com.tirthankar.ascent.feature.awakening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.repo.AwakeningInput
import com.tirthankar.ascent.data.repo.DailyCycle
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.engine.AwakeningMeasurements
import com.tirthankar.ascent.engine.BaselineInputs
import com.tirthankar.ascent.engine.Measurement
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.StartingStats
import com.tirthankar.ascent.engine.StatBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

enum class AwakeningStep { DETECTION, IDENTITY, MEASUREMENT, RHYTHM, CONFIRM }

/** Each Awakening number field: what you can do now, and your own goal. */
enum class MeasureField(val lowerIsBetter: Boolean = false) {
    PUSH_UPS, SQUATS, PLANK, STEPS, RUN_1K(lowerIsBetter = true), DEEP_WORK, PAGES, SLEEP,
}

enum class AgilityMode { STEPS, RUN_1K }

enum class TimeSetting { RESET, WAKE, QUIET_START, QUIET_END }

data class FieldInput(val current: String = "", val goal: String = "")

data class AwakeningUiState(
    val step: AwakeningStep = AwakeningStep.DETECTION,
    val name: String = "",
    val fields: Map<MeasureField, FieldInput> = DEFAULT_FIELDS,
    val agilityMode: AgilityMode = AgilityMode.STEPS,
    val settings: AscentSettings = AscentSettings(),
    val saving: Boolean = false,
) {
    val nameValid: Boolean get() = name.isNotBlank()
    val restDaysValid: Boolean get() = SafetyRules.hasEnoughRestDays(settings.restDays)

    /** Fields whose text isn't a non-negative number (empty is fine: it's optional). */
    val invalidFields: Set<MeasureField>
        get() = fields.filterValues { !it.current.isValidNumber() || !it.goal.isValidNumber(allowZero = false) }.keys

    val canContinue: Boolean
        get() = when (step) {
            AwakeningStep.DETECTION -> true
            AwakeningStep.IDENTITY -> nameValid
            AwakeningStep.MEASUREMENT -> invalidFields.isEmpty()
            AwakeningStep.RHYTHM -> restDaysValid
            AwakeningStep.CONFIRM -> !saving
        }

    /** The engine's starting stats for what's been entered so far. */
    val startingStats: StatBlock get() = StartingStats.compute(measurements())

    fun measurements(): AwakeningMeasurements {
        fun m(field: MeasureField): Measurement? {
            val input = fields[field] ?: return null
            val current = input.current.toNumber() ?: return null
            val goal = input.goal.toNumber()?.takeIf { it > 0 } ?: return null
            return Measurement(current, goal, field.lowerIsBetter)
        }
        return AwakeningMeasurements(
            pushUps = m(MeasureField.PUSH_UPS),
            squats = m(MeasureField.SQUATS),
            agility = if (agilityMode == AgilityMode.STEPS) m(MeasureField.STEPS) else m(MeasureField.RUN_1K),
            deepWorkHours = m(MeasureField.DEEP_WORK),
            pagesPerWeek = m(MeasureField.PAGES),
            sleepHours = m(MeasureField.SLEEP),
        )
    }

    fun baselines(): BaselineInputs {
        fun now(field: MeasureField) = fields[field]?.current?.toNumber()
        return BaselineInputs(
            maxPushUps = now(MeasureField.PUSH_UPS)?.toInt(),
            maxSquats = now(MeasureField.SQUATS)?.toInt(),
            maxPlankSeconds = now(MeasureField.PLANK)?.toInt(),
            averageSteps = if (agilityMode == AgilityMode.STEPS) now(MeasureField.STEPS)?.toInt() else null,
            deepWorkHoursPerDay = now(MeasureField.DEEP_WORK),
            pagesPerWeek = now(MeasureField.PAGES)?.toInt(),
        )
    }

    companion object {
        /** Suggested goals; you change them to your own. */
        val DEFAULT_FIELDS: Map<MeasureField, FieldInput> = mapOf(
            MeasureField.PUSH_UPS to FieldInput(goal = "30"),
            MeasureField.SQUATS to FieldInput(goal = "40"),
            MeasureField.PLANK to FieldInput(goal = "90"),
            MeasureField.STEPS to FieldInput(goal = "10000"),
            MeasureField.RUN_1K to FieldInput(goal = "5"),
            MeasureField.DEEP_WORK to FieldInput(goal = "4"),
            MeasureField.PAGES to FieldInput(goal = "70"),
            MeasureField.SLEEP to FieldInput(goal = "7.5"),
        )
    }
}

internal fun String.toNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

private fun String.isValidNumber(allowZero: Boolean = true): Boolean {
    if (isBlank()) return true
    val v = toNumber() ?: return false
    return allowZero || v > 0
}

sealed interface AwakeningEvent {
    data object Next : AwakeningEvent
    data object Back : AwakeningEvent
    data class NameChanged(val name: String) : AwakeningEvent
    data class CurrentChanged(val field: MeasureField, val text: String) : AwakeningEvent
    data class GoalChanged(val field: MeasureField, val text: String) : AwakeningEvent
    data class AgilityModeChanged(val mode: AgilityMode) : AwakeningEvent
    data class TimeStep(val setting: TimeSetting, val minutes: Long) : AwakeningEvent
    data class ToggleRestDay(val day: DayOfWeek) : AwakeningEvent
}

@HiltViewModel
class AwakeningViewModel @Inject constructor(
    private val players: PlayerRepository,
    private val cycle: DailyCycle,
) : ViewModel() {

    private val _state = MutableStateFlow(AwakeningUiState())
    val state: StateFlow<AwakeningUiState> = _state.asStateFlow()

    fun onEvent(event: AwakeningEvent) {
        when (event) {
            AwakeningEvent.Next -> next()
            AwakeningEvent.Back -> _state.update {
                if (it.step.ordinal == 0 || it.saving) it else it.copy(step = AwakeningStep.entries[it.step.ordinal - 1])
            }
            is AwakeningEvent.NameChanged -> _state.update { it.copy(name = event.name.take(MAX_NAME)) }
            is AwakeningEvent.CurrentChanged -> updateField(event.field) { it.copy(current = event.text.take(MAX_NUMBER)) }
            is AwakeningEvent.GoalChanged -> updateField(event.field) { it.copy(goal = event.text.take(MAX_NUMBER)) }
            is AwakeningEvent.AgilityModeChanged -> _state.update { it.copy(agilityMode = event.mode) }
            is AwakeningEvent.TimeStep -> _state.update { s ->
                val settings = s.settings
                s.copy(
                    settings = when (event.setting) {
                        TimeSetting.RESET -> settings.copy(resetTime = settings.resetTime.step(event.minutes))
                        TimeSetting.WAKE -> settings.copy(wakeTime = settings.wakeTime.step(event.minutes))
                        TimeSetting.QUIET_START -> settings.copy(quietStart = settings.quietStart.step(event.minutes))
                        TimeSetting.QUIET_END -> settings.copy(quietEnd = settings.quietEnd.step(event.minutes))
                    },
                )
            }
            is AwakeningEvent.ToggleRestDay -> _state.update { s ->
                val days = s.settings.restDays
                s.copy(settings = s.settings.copy(restDays = if (event.day in days) days - event.day else days + event.day))
            }
        }
    }

    private fun next() {
        val s = _state.value
        if (!s.canContinue) return
        if (s.step != AwakeningStep.CONFIRM) {
            _state.update { it.copy(step = AwakeningStep.entries[it.step.ordinal + 1]) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            players.createPlayer(AwakeningInput(s.name, s.measurements(), s.baselines(), s.settings))
            // Daily Quest #1 is issued straight away. The app then leaves the Awakening.
            cycle.ensureUpToDate()
        }
    }

    private fun updateField(field: MeasureField, change: (FieldInput) -> FieldInput) {
        _state.update { s -> s.copy(fields = s.fields + (field to change(s.fields[field] ?: FieldInput()))) }
    }

    private fun LocalTime.step(minutes: Long): LocalTime = plusMinutes(minutes)

    private companion object {
        const val MAX_NAME = 24
        const val MAX_NUMBER = 7
    }
}
