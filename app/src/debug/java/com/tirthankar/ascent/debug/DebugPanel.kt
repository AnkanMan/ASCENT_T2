package com.tirthankar.ascent.debug

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.tirthankar.ascent.R
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.debug.DebugActions
import com.tirthankar.ascent.data.repo.DailyCycle
import com.tirthankar.ascent.data.repo.SkillRepository
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Stat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject

/** Debug builds only (PHASE4.md section 9): shortcuts for testing classes, trials and evolutions. */
data class DebugUiState(
    val loaded: Boolean = false,
    val level: Int = 1,
    val rank: Rank = Rank.E,
    val mp: Int = 0,
    val maxMp: Int = 0,
    val stats: Map<Stat, Int> = emptyMap(),
    val freePoints: Int = 0,
    val skillPoints: Int = 0,
    val essence: Int = 0,
    val respecTokens: Int = 0,
    val classId: String? = null,
    val trialOpen: Boolean = false,
    val today: LocalDate = LocalDate.of(2026, 11, 15),
    val offsetDays: Long = 0,
    val busy: Boolean = false,
)

sealed interface DebugEvent {
    data class RaiseLevel(val by: Int) : DebugEvent
    data class SetRank(val rank: Rank) : DebugEvent
    data class ShiftStat(val stat: Stat, val by: Int) : DebugEvent
    data object FillMp : DebugEvent
    data object EmptyMp : DebugEvent
    data object GrantSkillPoint : DebugEvent
    data object GrantFreePoints : DebugEvent
    data object GrantEssence : DebugEvent
    data object GrantRespecToken : DebugEvent
    data object OfferClassChoice : DebugEvent
    data object ClearRankTrial : DebugEvent
    data class FastForward(val days: Long) : DebugEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DebugPanelViewModel @Inject constructor(
    private val actions: DebugActions,
    private val cycle: DailyCycle,
    private val clock: DebugClock,
    private val gameDay: GameDay,
    skills: SkillRepository,
) : ViewModel() {

    private val busy = MutableStateFlow(false)

    val uiState: StateFlow<DebugUiState> = combine(actions.snapshot, skills.unlocked, busy) { s, _, working -> s to working }
        .mapLatest { (s, working) ->
            s ?: return@mapLatest DebugUiState()
            DebugUiState(
                loaded = true,
                level = s.level,
                rank = s.rank,
                mp = s.mp,
                maxMp = s.maxMp,
                stats = s.stats,
                freePoints = s.freePoints,
                skillPoints = actions.skillPoints(),
                essence = s.essence,
                respecTokens = s.respecTokens,
                classId = s.classId,
                trialOpen = s.trialOpen,
                today = gameDay.today(),
                offsetDays = clock.offset.toDays(),
                busy = working,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugUiState())

    fun onEvent(event: DebugEvent) {
        viewModelScope.launch {
            busy.value = true
            try {
                apply(event)
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun apply(event: DebugEvent) {
        when (event) {
            is DebugEvent.RaiseLevel -> {
                actions.raiseLevel(event.by)
                cycle.ensureUpToDate() // level lore, and a rank trial if one is now due
            }
            is DebugEvent.SetRank -> actions.setRank(event.rank)
            is DebugEvent.ShiftStat -> actions.shiftStat(event.stat, event.by)
            DebugEvent.FillMp -> actions.fillMp()
            DebugEvent.EmptyMp -> actions.emptyMp()
            DebugEvent.GrantSkillPoint -> actions.grantSkillPoint()
            DebugEvent.GrantFreePoints -> actions.grantFreePoints(FREE_POINTS)
            DebugEvent.GrantEssence -> actions.grantEssence(ESSENCE)
            DebugEvent.GrantRespecToken -> actions.grantRespecToken()
            DebugEvent.OfferClassChoice -> actions.offerClassChoice()
            DebugEvent.ClearRankTrial -> actions.clearRankTrial()
            is DebugEvent.FastForward -> {
                clock.advance(Duration.ofDays(event.days))
                cycle.ensureUpToDate()
            }
        }
    }

    private companion object {
        const val FREE_POINTS = 3
        const val ESSENCE = 50
    }
}

/** The Settings row that opens the panel. */
@Composable
internal fun DebugMenuEntry(onOpen: () -> Unit) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.debug_header)) {
        OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.debug_open), color = AscentColors.LegendGold)
        }
    }
}

@Composable
internal fun DebugPanelRoute(onBack: () -> Unit, viewModel: DebugPanelViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DebugPanelScreen(state, viewModel::onEvent, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugPanelScreen(state: DebugUiState, onEvent: (DebugEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = state.loaded && !state.busy
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("← " + stringResource(R.string.debug_back), color = AscentColors.GlowCyan)
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.debug_header)) {
            Text(stringResource(R.string.debug_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
        }

        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.debug_player)) {
            ValueLine(stringResource(R.string.debug_level), state.level.toString())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10).forEach { n ->
                    ActionButton(stringResource(R.string.debug_level_up, n), enabled) { onEvent(DebugEvent.RaiseLevel(n)) }
                }
            }
            Label(stringResource(R.string.debug_rank))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Rank.entries.filter { it != Rank.SIGMA }.forEach { rank ->
                    FilterChip(
                        selected = rank == state.rank,
                        onClick = { onEvent(DebugEvent.SetRank(rank)) },
                        enabled = enabled,
                        label = { Text(rank.name, style = AscentTextStyles.Number) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AscentColors.SystemBlue.copy(alpha = 0.3f),
                            labelColor = AscentColors.Mist,
                            selectedLabelColor = AscentColors.Mist,
                        ),
                    )
                }
            }
            ValueLine(stringResource(R.string.debug_mp), stringResource(R.string.debug_of, state.mp, state.maxMp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.debug_mp_fill), enabled) { onEvent(DebugEvent.FillMp) }
                ActionButton(stringResource(R.string.debug_mp_empty), enabled) { onEvent(DebugEvent.EmptyMp) }
            }
        }

        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.debug_stats)) {
            Stat.entries.forEach { stat ->
                StatLine(stat, state.stats[stat] ?: 0, enabled) { by -> onEvent(DebugEvent.ShiftStat(stat, by)) }
            }
        }

        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.debug_grants)) {
            ValueLine(stringResource(R.string.debug_skill_points), state.skillPoints.toString())
            ValueLine(stringResource(R.string.debug_free_points), state.freePoints.toString())
            ValueLine(stringResource(R.string.debug_essence), state.essence.toString())
            ValueLine(stringResource(R.string.debug_respec_tokens), state.respecTokens.toString())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.debug_grant_skill_point), enabled) { onEvent(DebugEvent.GrantSkillPoint) }
                ActionButton(stringResource(R.string.debug_grant_free_points), enabled) { onEvent(DebugEvent.GrantFreePoints) }
                ActionButton(stringResource(R.string.debug_grant_essence), enabled) { onEvent(DebugEvent.GrantEssence) }
                ActionButton(stringResource(R.string.debug_grant_token), enabled) { onEvent(DebugEvent.GrantRespecToken) }
            }
        }

        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.debug_progression)) {
            ValueLine(stringResource(R.string.debug_class), state.classId ?: stringResource(R.string.debug_none))
            ValueLine(
                stringResource(R.string.debug_trial),
                stringResource(if (state.trialOpen) R.string.debug_trial_open else R.string.debug_none),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.debug_offer_class), enabled && state.classId != null) { onEvent(DebugEvent.OfferClassChoice) }
                ActionButton(stringResource(R.string.debug_clear_trial), enabled && state.trialOpen) { onEvent(DebugEvent.ClearRankTrial) }
            }
        }

        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.debug_time)) {
            ValueLine(stringResource(R.string.debug_game_day), state.today.toString())
            ValueLine(stringResource(R.string.debug_offset), stringResource(R.string.debug_days, state.offsetDays))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.debug_forward_day), enabled) { onEvent(DebugEvent.FastForward(1)) }
                ActionButton(stringResource(R.string.debug_forward_week), enabled) { onEvent(DebugEvent.FastForward(7)) }
            }
            Text(stringResource(R.string.debug_forward_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.PenaltyRed)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
        Text(value, style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, color = if (enabled) AscentColors.GlowCyan else AscentColors.Mist.copy(alpha = 0.4f))
    }
}

@Composable
private fun StatLine(stat: Stat, value: Int, enabled: Boolean, onShift: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stat.name, style = AscentTextStyles.Number, color = AscentColors.Mist, modifier = Modifier.weight(1f))
        val lower = stringResource(R.string.debug_stat_lower, stat.name)
        IconButton(onClick = { onShift(-STAT_STEP) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = lower }) {
            Text("−", style = AscentTextStyles.NumberLarge, color = AscentColors.GlowCyan, modifier = Modifier.clearAndSetSemantics { })
        }
        Text(value.toString(), style = AscentTextStyles.Number, color = AscentColors.Mist)
        val raise = stringResource(R.string.debug_stat_raise, stat.name)
        IconButton(onClick = { onShift(STAT_STEP) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = raise }) {
            Text("+", style = AscentTextStyles.NumberLarge, color = AscentColors.GlowCyan, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

private const val STAT_STEP = 5

@Preview(name = "Debug panel", widthDp = 400, heightDp = 1800)
@Composable
private fun DebugPanelPreview() {
    AscentTheme {
        SystemBackground {
            DebugPanelScreen(
                DebugUiState(
                    loaded = true,
                    level = 10,
                    rank = Rank.E,
                    mp = 40,
                    maxMp = 120,
                    stats = mapOf(Stat.STR to 14, Stat.AGI to 12, Stat.INT to 31, Stat.FOC to 18, Stat.WIS to 27, Stat.VIT to 11),
                    freePoints = 3,
                    skillPoints = 5,
                    essence = 120,
                    respecTokens = 1,
                    classId = "theorist",
                    trialOpen = true,
                    offsetDays = 9,
                ),
                onEvent = {},
                onBack = {},
            )
        }
    }
}
