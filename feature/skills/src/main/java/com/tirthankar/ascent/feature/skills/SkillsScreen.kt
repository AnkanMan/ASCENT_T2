package com.tirthankar.ascent.feature.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirthankar.ascent.bridge.ShareBridge
import com.tirthankar.ascent.data.repo.RespecCost
import com.tirthankar.ascent.data.repo.SkillStatus
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.PowerHandlers
import com.tirthankar.ascent.engine.PowerRules
import com.tirthankar.ascent.engine.SkillDef
import com.tirthankar.ascent.engine.SkillKind
import com.tirthankar.ascent.engine.SkillRules
import com.tirthankar.ascent.engine.SkillTree
import com.tirthankar.ascent.engine.UnlockCheck

@Composable
fun SkillsRoute(modifier: Modifier = Modifier, viewModel: SkillsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooser = stringResource(R.string.skills_share_chooser)
    // Insight, Mentor's Eye and Collapse hand a request to the share sheet (A7, A8).
    LaunchedEffect(state.share) {
        val text = state.share ?: return@LaunchedEffect
        context.startActivity(ShareBridge.shareIntent(text, chooser))
        viewModel.onEvent(SkillsEvent.ShareShown)
    }
    if (!state.loaded) return
    Materialize(modifier) { SkillsScreen(state, viewModel::onEvent) }
    when (state.input) {
        is PowerInput.Clarity -> ClarityDialog(viewModel::onEvent)
        is PowerInput.Insight -> InsightDialog(viewModel::onEvent)
        null -> Unit
    }
}

/** The Skills tab (PHASE4.md section 9): points, respec, the tree graph and a node sheet. Stateless. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SkillsScreen(state: SkillsUiState, onEvent: (SkillsEvent) -> Unit, modifier: Modifier = Modifier) {
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var confirmRespec by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.skills_header)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.skills_points), style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
                Text(state.points.toString(), style = AscentTextStyles.NumberLarge, color = if (state.points > 0) AscentColors.LegendGold else AscentColors.Mist)
            }
            state.classSkill?.let { cls ->
                TextButton(onClick = { onEvent(SkillsEvent.Select(cls.def.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.skills_tree_class) + " · " + cls.def.name, color = AscentColors.ArcaneViolet)
                }
            }
            RespecButton(state.respec) { confirmRespec = true }
            state.message?.let { Text(messageText(it, state), style = MaterialTheme.typography.bodyMedium, color = AscentColors.GlowCyan) }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkillGraphLayout.TREES.forEachIndexed { index, tree ->
                FilterChip(
                    selected = false,
                    onClick = { pan = Offset(-with(density) { GraphMetrics.treeX(index).toPx() } * zoom, 0f) },
                    label = { Text(treeName(tree)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(labelColor = if (tree == SkillTree.OBSERVER) AscentColors.ArcaneViolet else AscentColors.Mist),
                )
            }
            TextButton(onClick = { pan = Offset.Zero; zoom = 1f }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.skills_reset_view), color = AscentColors.GlowCyan)
            }
        }
        Text(stringResource(R.string.skills_hint), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.6f))

        SkillGraph(
            skills = state.skills,
            pan = pan,
            zoom = zoom,
            onTransform = { p, z ->
                zoom = (zoom * z).coerceIn(MIN_ZOOM, MAX_ZOOM)
                pan += p
            },
            onSelect = { onEvent(SkillsEvent.Select(it)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    state.selected?.let { selected ->
        ModalBottomSheet(onDismissRequest = { onEvent(SkillsEvent.CloseSheet) }, containerColor = AscentColors.PanelSolid) {
            SkillSheet(selected, state, onEvent, Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp))
        }
    }

    if (confirmRespec) {
        AlertDialog(
            onDismissRequest = { confirmRespec = false },
            title = { Text(stringResource(R.string.skills_respec_confirm_title)) },
            text = { Text(stringResource(R.string.skills_respec_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { confirmRespec = false; onEvent(SkillsEvent.Respec) }) {
                    Text(stringResource(R.string.skills_respec_confirm), color = AscentColors.LegendGold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRespec = false }) { Text(stringResource(R.string.skills_cancel)) }
            },
            containerColor = AscentColors.PanelSolid,
        )
    }
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 1.6f

@Composable
private fun RespecButton(cost: RespecCost, onClick: () -> Unit) {
    if (cost == RespecCost.NothingToReturn) return
    val label = when (cost) {
        RespecCost.Token -> stringResource(R.string.skills_respec_token)
        is RespecCost.Essence -> stringResource(R.string.skills_respec_essence, cost.amount)
        is RespecCost.CannotAfford -> stringResource(R.string.skills_respec_cannot, cost.essenceNeeded)
        RespecCost.NothingToReturn -> stringResource(R.string.skills_respec)
    }
    val enabled = cost == RespecCost.Token || cost is RespecCost.Essence
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, color = if (enabled) AscentColors.GlowCyan else AscentColors.Mist.copy(alpha = 0.5f))
    }
}

/** The node sheet: what it does, what it costs, what it needs, and Unlock or Cast. */
@Composable
internal fun SkillSheet(status: SkillStatus, state: SkillsUiState, onEvent: (SkillsEvent) -> Unit, modifier: Modifier = Modifier) {
    val def = status.def
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.look() == NodeLook.SEALED) {
            Text(treeName(SkillTree.OBSERVER), style = AscentTextStyles.SystemHeader, color = AscentColors.ArcaneViolet)
            Text(stringResource(R.string.skills_observer_hint), style = MaterialTheme.typography.titleLarge, color = AscentColors.Mist)
            return@Column
        }
        Text(def.name, style = MaterialTheme.typography.titleLarge, color = AscentColors.LegendGold)
        Text(kindLine(def), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
        Text(def.description, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
        if (def.kind == SkillKind.ACTIVE) {
            costLine(def)?.let { Text(it, style = AscentTextStyles.Number, color = AscentColors.GlowCyan) }
        }
        if (!status.unlocked) {
            val ready = status.check == UnlockCheck.Ok
            Text(
                unlockText(status.check, def, state),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ready) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.8f),
            )
            OutlinedButton(onClick = { onEvent(SkillsEvent.Unlock(def.id)) }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.skills_unlock), color = if (ready) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.5f))
            }
        } else if (def.kind == SkillKind.ACTIVE) {
            val check = state.casts[def.id]
            if (def.handler in PowerRules.AUTOMATIC) {
                Text(stringResource(R.string.skills_automatic), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
            } else if (check != null) {
                CastControls(def, check, onEvent)
            }
            if (def.handler == PowerHandlers.EIGENSTATE_COLLAPSE) {
                TextButton(onClick = { onEvent(SkillsEvent.AskWhichOne) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.skills_ask_which), color = AscentColors.GlowCyan)
                }
            }
        } else {
            Text(stringResource(R.string.skills_state_unlocked), style = AscentTextStyles.SystemLabel, color = AscentColors.GlowCyan)
        }
        state.message?.let { Text(messageText(it, state), style = MaterialTheme.typography.bodyMedium, color = AscentColors.GlowCyan) }
    }
}

/** Clarity's three lines (Task 16). They stay on this phone; the third becomes tomorrow's quests. */
@Composable
private fun ClarityDialog(onEvent: (SkillsEvent) -> Unit) {
    var moved by rememberSaveable { mutableStateOf("") }
    var stuck by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onEvent(SkillsEvent.CloseInput) },
        title = { Text(stringResource(R.string.skills_clarity_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(moved, { moved = it }, label = { Text(stringResource(R.string.skills_clarity_moved)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(stuck, { stuck = it }, label = { Text(stringResource(R.string.skills_clarity_stuck)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(next, { next = it }, label = { Text(stringResource(R.string.skills_clarity_next)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.skills_clarity_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SkillsEvent.SubmitClarity(moved, stuck, next)) }, enabled = next.isNotBlank()) {
                Text(stringResource(R.string.skills_cast), color = AscentColors.LegendGold)
            }
        },
        dismissButton = { TextButton(onClick = { onEvent(SkillsEvent.CloseInput) }) { Text(stringResource(R.string.skills_cancel)) } },
        containerColor = AscentColors.PanelSolid,
    )
}

/** Insight's stuck problem (Task 18): shared with your status so Claude can split it (A7). */
@Composable
private fun InsightDialog(onEvent: (SkillsEvent) -> Unit) {
    var problem by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onEvent(SkillsEvent.CloseInput) },
        title = { Text(stringResource(R.string.skills_insight_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(problem, { problem = it }, label = { Text(stringResource(R.string.skills_insight_problem)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.skills_insight_note), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SkillsEvent.SubmitInsight(problem)) }, enabled = problem.isNotBlank()) {
                Text(stringResource(R.string.skills_cast), color = AscentColors.LegendGold)
            }
        },
        dismissButton = { TextButton(onClick = { onEvent(SkillsEvent.CloseInput) }) { Text(stringResource(R.string.skills_cancel)) } },
        containerColor = AscentColors.PanelSolid,
    )
}

/** Cast with a second tap to confirm, so MP is never spent by accident. */
@Composable
private fun CastControls(def: SkillDef, check: CastCheck, onEvent: (SkillsEvent) -> Unit) {
    var armed by remember(def.id) { mutableStateOf(false) }
    val ready = check == CastCheck.Ok
    Text(castText(check), style = MaterialTheme.typography.bodyMedium, color = if (ready) AscentColors.GlowCyan else AscentColors.Mist.copy(alpha = 0.8f))
    OutlinedButton(
        onClick = {
            if (armed) {
                armed = false
                onEvent(SkillsEvent.Cast(def.id))
            } else {
                armed = true
            }
        },
        enabled = ready,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        val cost = costLine(def) ?: ""
        Text(
            if (armed) stringResource(R.string.skills_cast_confirm, cost) else stringResource(R.string.skills_cast),
            color = if (ready) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.5f),
        )
    }
}

// ---- Text ----

@Composable
private fun treeName(tree: SkillTree): String = stringResource(
    when (tree) {
        SkillTree.BODY -> R.string.skills_tree_body
        SkillTree.MIND -> R.string.skills_tree_mind
        SkillTree.SELF -> R.string.skills_tree_self
        SkillTree.OBSERVER -> R.string.skills_tree_observer
        SkillTree.CLASS -> R.string.skills_tree_class
    },
)

@Composable
private fun kindLine(def: SkillDef): String {
    val kind = stringResource(
        when {
            def.tier == SkillRules.ULTIMATE_TIER -> R.string.skills_kind_ultimate
            def.kind == SkillKind.PASSIVE -> R.string.skills_kind_passive
            else -> R.string.skills_kind_active
        },
    )
    return if (def.tree == SkillTree.CLASS) {
        treeName(def.tree) + " · " + kind
    } else {
        treeName(def.tree) + " · " + stringResource(R.string.skills_tier, def.tier) + " · " + kind
    }
}

@Composable
private fun unlockText(check: UnlockCheck, def: SkillDef, state: SkillsUiState): String = when (check) {
    UnlockCheck.Ok -> stringResource(R.string.skills_state_ready)
    UnlockCheck.AlreadyUnlocked -> stringResource(R.string.skills_state_unlocked)
    UnlockCheck.NoPoints -> stringResource(R.string.skills_need_point)
    UnlockCheck.Locked -> stringResource(R.string.skills_need_locked)
    UnlockCheck.NeedsBoss -> stringResource(R.string.skills_need_boss)
    is UnlockCheck.MissingPrerequisite -> stringResource(R.string.skills_need_prerequisite, state.name(check.skillId))
    is UnlockCheck.GateStat -> {
        val stats = state.treeStats[def.tree].orEmpty()
        stringResource(
            R.string.skills_need_gate,
            stats.getOrNull(0)?.name.orEmpty(),
            stats.getOrNull(1)?.name.orEmpty(),
            check.needed,
            check.have,
        )
    }
}

@Composable
private fun messageText(message: SkillMessage, state: SkillsUiState): String = when (message) {
    is SkillMessage.Unlocked -> stringResource(R.string.skills_msg_unlocked, message.name)
    is SkillMessage.NotUnlocked -> state.selected?.let { unlockText(message.check, it.def, state) } ?: stringResource(R.string.skills_state_locked)
    is SkillMessage.Cast -> stringResource(R.string.skills_msg_cast, message.name, message.mp)
    is SkillMessage.Refused -> castText(message.check)
    is SkillMessage.Respecced -> stringResource(R.string.skills_msg_respecced, message.points)
    SkillMessage.RespecFailed -> stringResource(R.string.skills_msg_respec_failed)
}

// ---- Previews ----

@Preview(name = "Skills", widthDp = 400, heightDp = 860)
@Composable
private fun SkillsPreview() {
    AscentTheme {
        SystemBackground {
            SkillsScreen(SampleSkills.state, {})
        }
    }
}

@Preview(name = "Skills — node sheet", widthDp = 400, heightDp = 420)
@Composable
private fun SkillSheetPreview() {
    AscentTheme {
        SystemBackground {
            val state = SampleSkills.state
            SkillSheet(state.skills.first { it.def.id == "limit_break" }, state, {}, Modifier.padding(24.dp))
        }
    }
}

@Preview(name = "Skills — cast sheet", widthDp = 400, heightDp = 420)
@Composable
private fun SkillCastSheetPreview() {
    AscentTheme {
        SystemBackground {
            val state = SampleSkills.state
            SkillSheet(state.skills.first { it.def.id == "hyperfocus" }, state, {}, Modifier.padding(24.dp))
        }
    }
}
