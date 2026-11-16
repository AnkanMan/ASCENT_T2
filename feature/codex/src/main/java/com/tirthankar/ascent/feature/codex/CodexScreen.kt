package com.tirthankar.ascent.feature.codex

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.model.AchievementProgress
import com.tirthankar.ascent.data.model.LoreLine
import com.tirthankar.ascent.data.model.RecordProgress
import com.tirthankar.ascent.engine.LoreCategory
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChip
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.flow.MutableStateFlow
import com.tirthankar.ascent.data.repo.AchievementRepository
import com.tirthankar.ascent.data.repo.LoreRepository
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class CodexUiState(
    val achievements: List<AchievementProgress> = emptyList(),
    val titles: List<String> = emptyList(),
    val wearing: String = "",
    /** The Record (PHASE4.md section 6), newest first. */
    val lore: List<LoreLine> = emptyList(),
    val record: RecordProgress = RecordProgress(0, 0),
    /** Null shows every category. */
    val filter: LoreCategory? = null,
) {
    val shown: List<LoreLine> get() = if (filter == null) lore else lore.filter { it.category == filter }
}

sealed interface CodexEvent {
    data class Wear(val title: String) : CodexEvent
    data class Filter(val category: LoreCategory?) : CodexEvent
    /** Opening an entry clears its unread dot. */
    data class Read(val id: String) : CodexEvent
}

@HiltViewModel
class CodexViewModel @Inject constructor(
    private val achievements: AchievementRepository,
    private val lore: LoreRepository,
    players: PlayerRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<LoreCategory?>(null)

    val uiState: StateFlow<CodexUiState> =
        combine(
            combine(achievements.achievements, achievements.titlesEarned, players.profile) { list, titles, profile -> Triple(list, titles, profile?.title.orEmpty()) },
            lore.unlocked,
            lore.record,
            filter,
        ) { (list, titles, wearing), lines, record, f ->
            CodexUiState(list, titles, wearing, lines.reversed(), record, f)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CodexUiState())

    init {
        // Fills in progress numbers (and backfills tiers) when the Codex opens.
        viewModelScope.launch { achievements.evaluate() }
    }

    fun onEvent(event: CodexEvent) {
        when (event) {
            is CodexEvent.Wear -> viewModelScope.launch { achievements.wear(event.title) }
            is CodexEvent.Filter -> filter.value = event.category
            is CodexEvent.Read -> viewModelScope.launch { lore.markRead(event.id) }
        }
    }
}

@Composable
fun CodexRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: CodexViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Materialize(modifier) { CodexScreen(state, viewModel::onEvent, onBack) }
}

/** The Codex (Task 33): achievements, titles to wear, and unlocked lore. Stateless. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodexScreen(state: CodexUiState, onEvent: (CodexEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("← " + stringResource(R.string.codex_back), color = AscentColors.GlowCyan)
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.codex_achievements)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.achievements.forEach { AchievementTile(it) }
            }
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.codex_titles)) {
            state.titles.forEach { title ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
                    if (title == state.wearing) {
                        Text(stringResource(R.string.codex_wearing), style = AscentTextStyles.SystemLabel, color = AscentColors.LegendGold)
                    } else {
                        TextButton(onClick = { onEvent(CodexEvent.Wear(title)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.codex_wear), color = AscentColors.GlowCyan)
                        }
                    }
                }
            }
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.codex_lore)) {
            // "n of m recorded": locked and hidden lines are counted, never named.
            Text(
                stringResource(R.string.codex_recorded, state.record.recorded, state.record.total),
                style = AscentTextStyles.Number,
                color = AscentColors.ArcaneViolet,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                FilterChip(state.filter == null, { onEvent(CodexEvent.Filter(null)) }, label = { Text(stringResource(R.string.codex_filter_all)) }, modifier = Modifier.heightIn(min = 48.dp))
                LoreCategory.entries.forEach { c ->
                    FilterChip(state.filter == c, { onEvent(CodexEvent.Filter(c)) }, label = { Text(stringResource(c.labelRes())) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            if (state.shown.isEmpty()) Text(stringResource(R.string.codex_no_lore), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
            state.shown.forEach { line ->
                val description = if (line.unread) stringResource(R.string.codex_unread_description, line.title) else line.title
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = line.unread, onClickLabel = stringResource(R.string.codex_mark_read)) { onEvent(CodexEvent.Read(line.id)) }
                        .padding(vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (line.unread) Text("● ", color = AscentColors.ArcaneViolet)
                        Text(line.title, style = MaterialTheme.typography.titleMedium, color = AscentColors.LegendGold)
                    }
                    Text(line.text, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
                    line.context?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AscentColors.Mist.copy(alpha = 0.6f)) }
                }
            }
        }
    }
}

@Composable
private fun AchievementTile(a: AchievementProgress) {
    val earned = a.tier > 0
    val secret = a.hidden && !earned
    val color = if (earned) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.5f)
    Column(
        Modifier
            .width(150.dp)
            .border(1.dp, color)
            .padding(8.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(if (secret) "???" else a.name + tierSuffix(a), style = MaterialTheme.typography.bodyMedium, color = color)
        Text(
            if (secret) stringResource(R.string.codex_hidden) else a.description,
            style = MaterialTheme.typography.labelSmall,
            color = AscentColors.Mist.copy(alpha = 0.75f),
        )
        val next = a.nextThreshold
        if (!secret && next != null) {
            Text(
                stringResource(R.string.codex_progress, number(a.progress), number(next)),
                style = AscentTextStyles.Number,
                color = AscentColors.GlowCyan,
            )
        }
    }
}

private fun LoreCategory.labelRes(): Int = when (this) {
    LoreCategory.WORLD -> R.string.codex_cat_world
    LoreCategory.RANKS -> R.string.codex_cat_ranks
    LoreCategory.CLASSES -> R.string.codex_cat_classes
    LoreCategory.SKILLS -> R.string.codex_cat_skills
    LoreCategory.ARCS -> R.string.codex_cat_arcs
    LoreCategory.BOSSES -> R.string.codex_cat_bosses
    LoreCategory.GATES -> R.string.codex_cat_gates
    LoreCategory.ACHIEVEMENTS -> R.string.codex_cat_achievements
}

private fun tierSuffix(a: AchievementProgress): String =
    if (a.tiers > 1 && a.tier > 0) " " + listOf("I", "II", "III").getOrElse(a.tier - 1) { "${a.tier}" } else ""

private fun number(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)

@Preview(name = "Codex", widthDp = 400, heightDp = 1300)
@Composable
private fun CodexPreview() {
    AscentTheme {
        SystemBackground {
            CodexScreen(
                CodexUiState(
                    achievements = listOf(
                        AchievementProgress("centurion", "Centurion", "Complete 100 Daily Quests.", false, 0, 1, 12.0, 100.0),
                        AchievementProgress("deep_field", "Deep Field", "Log 25, 100 and 400 hours of deep work.", false, 1, 3, 31.0, 100.0),
                        AchievementProgress("equipartition", "Equipartition", "Bring all six stats within 10% of each other.", false, 1, 1, 1.0, null),
                        AchievementProgress("still_night", "Still Night", "Fourteen nights in a row at your sleep target.", true, 0, 1, 3.0, 14.0),
                    ),
                    titles = listOf("The Unmeasured", "Equipartitioned"),
                    wearing = "The Unmeasured",
                    lore = listOf(
                        LoreLine("lore_rank_d", "First excited state", "You left the ground state and stayed out.", null, LoreCategory.RANKS, unread = true),
                        LoreLine("lore_awakening", "The excitation", "Most of the lattice sleeps in its ground state. You did not. The Observer has begun to measure.", null, LoreCategory.WORLD),
                    ),
                    record = RecordProgress(23, 68),
                ),
                onEvent = {},
                onBack = {},
            )
        }
    }
}
