package com.tirthankar.ascent.feature.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.repo.ClassRepository
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.LocalReducedMotion
import com.tirthankar.ascent.designsystem.SigilRing
import com.tirthankar.ascent.engine.ClassRules
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
import javax.inject.Inject

/** One class on offer: its name at your rank, its two stats, and its unique skill. */
data class ClassCard(
    val id: String,
    val name: String,
    val stats: List<Stat>,
    val uniqueName: String,
    val uniqueText: String,
    val polymath: Boolean,
)

/** Class Awakening (level 10) or the re-choice after a rank-up (A5, A14). */
data class ClassChoiceUi(
    val rechoice: Boolean,
    val current: ClassCard?,
    val options: List<ClassCard>,
    val stats: Map<Stat, Int>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ClassAwakeningViewModel @Inject constructor(
    private val classes: ClassRepository,
    players: PlayerRepository,
) : ViewModel() {

    /** "Later" hides the offer until the app is opened again. */
    private val postponed = MutableStateFlow(false)

    val uiState: StateFlow<ClassChoiceUi?> = combine(classes.choiceDue, players.profile, postponed) { due, profile, later -> Triple(due, profile, later) }
        .mapLatest { (due, profile, later) ->
            if (!due || later || profile == null) return@mapLatest null
            val rank = profile.rank
            fun card(id: String): ClassCard? = classes.definition(id)?.let { def ->
                ClassCard(def.id, ClassRules.displayName(def, rank), def.stats, def.unique.name, def.unique.description, def.polymath)
            }
            ClassChoiceUi(
                rechoice = profile.classId != null,
                current = profile.classId?.let(::card),
                options = classes.choices().mapNotNull { card(it.classId) }.filter { it.id != profile.classId },
                stats = Stat.entries.associateWith { profile.stats[it] },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun choose(id: String) {
        viewModelScope.launch { classes.choose(id) }
    }

    fun later() {
        postponed.value = true
    }
}

/** Shows Class Awakening over the app when a class choice is due. Place once in the app shell. */
@Composable
fun ClassAwakeningHost(viewModel: ClassAwakeningViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    state?.let { ClassAwakeningScreen(it, onChoose = viewModel::choose, onLater = viewModel::later) }
}

/**
 * The cinematic (the six stats rise as columns, the leading two ignite, the sigil forms),
 * then the choice. Skipped straight to the choice with reduced motion.
 */
@Composable
fun ClassAwakeningScreen(state: ClassChoiceUi, onChoose: (String) -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val rise = remember { Animatable(if (reduced || state.rechoice) 1f else 0f) }
    val ignite = remember { Animatable(if (reduced || state.rechoice) 1f else 0f) }
    val sigil = remember { Animatable(if (reduced || state.rechoice) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced || state.rechoice) return@LaunchedEffect
        rise.animateTo(1f, tween(1_200, easing = FastOutSlowInEasing))
        ignite.animateTo(1f, tween(500))
        sigil.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val leading = state.stats.entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val columnsDescription = stringResource(
        R.string.class_columns_description,
        state.stats.entries.joinToString(", ") { "${it.key.name} ${it.value}" },
    )
    Box(
        modifier
            .fillMaxSize()
            .background(AscentColors.Void.copy(alpha = 0.94f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (state.rechoice) R.string.class_rechoice_header else R.string.class_awakening_header),
                style = AscentTextStyles.SystemHeader,
                color = AscentColors.ArcaneViolet,
            )
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = columnsDescription }) {
                    val max = (state.stats.values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
                    val n = Stat.entries.size
                    val slot = size.width / n
                    Stat.entries.forEachIndexed { i, stat ->
                        val v = state.stats[stat] ?: 0
                        val h = size.height * 0.85f * (v / max) * rise.value
                        val lead = stat in leading
                        val color = if (lead) AscentColors.LegendGold.copy(alpha = 0.35f + 0.65f * ignite.value) else AscentColors.SystemBlue.copy(alpha = 0.55f)
                        drawRect(color, Offset(slot * i + slot * 0.25f, size.height - h), Size(slot * 0.5f, h))
                    }
                }
                if (sigil.value > 0f) SigilRing(sigil.value, AscentColors.ArcaneViolet, Modifier.size(110.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Stat.entries.forEach { Text(it.name, style = AscentTextStyles.SystemLabel, color = if (it in leading) AscentColors.LegendGold else AscentColors.Mist) }
            }
            if (sigil.value >= 1f) {
                Text(
                    stringResource(if (state.rechoice) R.string.class_rechoice_intro else R.string.class_awakening_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AscentColors.Mist,
                )
                state.current?.let { ClassOptionCard(it, picked == it.id, keep = true) { picked = it.id } }
                state.options.forEach { ClassOptionCard(it, picked == it.id, keep = false) { picked = it.id } }
                val chosen = (listOfNotNull(state.current) + state.options).firstOrNull { it.id == picked }
                OutlinedButton(onClick = { chosen?.let { onChoose(it.id) } }, enabled = chosen != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        chosen?.let { stringResource(if (it.id == state.current?.id) R.string.class_keep else R.string.class_take, it.name) }
                            ?: stringResource(R.string.class_pick),
                        color = if (chosen != null) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.5f),
                    )
                }
                TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.class_later), color = AscentColors.Mist.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun ClassOptionCard(card: ClassCard, selected: Boolean, keep: Boolean, onSelect: () -> Unit) {
    val border = if (selected) AscentColors.LegendGold else AscentColors.ArcaneViolet.copy(alpha = 0.5f)
    Column(
        Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, border)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(card.name, style = MaterialTheme.typography.titleMedium, color = if (selected) AscentColors.LegendGold else AscentColors.Mist)
        Text(
            if (card.polymath) stringResource(R.string.class_bonus_polymath) else stringResource(R.string.class_bonus, card.stats.joinToString("/") { it.name }),
            style = AscentTextStyles.SystemLabel,
            color = AscentColors.SystemBlue,
        )
        Text(card.uniqueName + " · " + card.uniqueText, style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.8f))
        if (keep) Text(stringResource(R.string.class_current), style = AscentTextStyles.SystemLabel, color = AscentColors.GlowCyan)
    }
}

private val previewChoice = ClassChoiceUi(
    rechoice = false,
    current = null,
    options = listOf(
        ClassCard("theorist", "Theorist", listOf(Stat.INT, Stat.WIS), "First Principles", "Once a day, replace one open Mind daily with a derivation you name.", false),
        ClassCard("computationalist", "Computationalist", listOf(Stat.INT, Stat.FOC), "Parallel Runs", "Link two timed Mind quests to one session.", false),
    ),
    stats = mapOf(Stat.STR to 12, Stat.AGI to 11, Stat.INT to 24, Stat.FOC to 17, Stat.WIS to 19, Stat.VIT to 10),
)

@Preview(name = "Class Awakening", widthDp = 400, heightDp = 900)
@Composable
private fun ClassAwakeningPreview() {
    // Reduced motion shows the end of the cinematic, with the choice.
    AscentTheme {
        androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {
            ClassAwakeningScreen(previewChoice, {}, {})
        }
    }
}

@Preview(name = "Class re-choice", widthDp = 400, heightDp = 900)
@Composable
private fun ClassRechoicePreview() {
    AscentTheme {
        ClassAwakeningScreen(
            previewChoice.copy(
                rechoice = true,
                current = ClassCard("vanguard", "Warbringer", listOf(Stat.STR, Stat.AGI), "Overload", "Once a week, every Body quest today pays 2× XP.", false),
            ),
            {},
            {},
        )
    }
}
