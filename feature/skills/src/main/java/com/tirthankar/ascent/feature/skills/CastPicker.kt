package com.tirthankar.ascent.feature.skills

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import com.tirthankar.ascent.engine.PowerHandlers
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tirthankar.ascent.data.repo.CastOption
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.CastConfirmCard
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.LimitPeriod
import com.tirthankar.ascent.engine.SkillDef
import com.tirthankar.ascent.engine.SkillKind
import com.tirthankar.ascent.engine.SkillLimit
import com.tirthankar.ascent.engine.SkillTree
import java.text.NumberFormat

/**
 * Casting on a target (PHASE4.md section 2): the powers you hold that take it, each with
 * its one-line reason when it can't be cast; picking one shows the confirm card (cost,
 * effect, target). Shared by the Quests tab (long-press) and the Boss Arena.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPickerSheet(
    targetTitle: String,
    options: List<CastOption>,
    onCast: (skillId: String, input: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AscentColors.PanelSolid) {
        CastPicker(targetTitle, options, onCast, Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp))
    }
}

@Composable
internal fun CastPicker(
    targetTitle: String,
    options: List<CastOption>,
    onCast: (skillId: String, input: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by remember { mutableStateOf<String?>(null) }
    // First Principles names its derivation; Parallel Runs picks its second quest.
    var input by remember(chosen) { mutableStateOf("") }
    val option = options.firstOrNull { it.skill.id == chosen }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (option == null) {
            Text(stringResource(R.string.cast_pick_header), style = AscentTextStyles.SystemHeader, color = AscentColors.SystemBlue)
            Text(stringResource(R.string.cast_on, targetTitle), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
            if (options.isEmpty()) {
                Text(stringResource(R.string.cast_none), style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist.copy(alpha = 0.7f))
            }
            options.forEach { o -> OptionRow(o) { chosen = o.skill.id } }
        } else {
            val lines = buildList {
                costLine(option.skill, option.mpCost)?.let(::add)
                add(option.skill.description)
                add(stringResource(R.string.cast_on, targetTitle))
                option.newTarget?.let { add(stringResource(R.string.cast_new_target, NumberFormat.getNumberInstance().format(it))) }
            }
            val handler = option.skill.handler
            CastConfirmCard(
                header = stringResource(R.string.cast_header),
                name = option.skill.name,
                lines = lines,
                reason = if (option.check == CastCheck.Ok) null else castText(option.check),
                confirmLabel = stringResource(R.string.cast_confirm),
                cancelLabel = stringResource(R.string.cast_cancel),
                onConfirm = { onCast(option.skill.id, input.ifBlank { null }) },
                onCancel = { chosen = null },
                enabled = (handler != PowerHandlers.FIRST_PRINCIPLES && handler != PowerHandlers.PARALLEL_RUNS) || input.isNotBlank(),
                extra = {
                    when (handler) {
                        PowerHandlers.FIRST_PRINCIPLES -> OutlinedTextField(
                            input,
                            { input = it },
                            label = { Text(stringResource(R.string.cast_derivation_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        PowerHandlers.PARALLEL_RUNS -> Column(Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.cast_parallel_pick), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
                            option.partners.forEach { (id, title) ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .selectable(selected = input == id.toString(), role = Role.RadioButton, onClick = { input = id.toString() }),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = input == id.toString(), onClick = null)
                                    Text(title, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun OptionRow(option: CastOption, onClick: () -> Unit) {
    val ready = option.check == CastCheck.Ok
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(1.dp, if (ready) AscentColors.GlowCyan else AscentColors.SystemBlue.copy(alpha = 0.4f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.skill.name, style = MaterialTheme.typography.titleMedium, color = if (ready) AscentColors.GlowCyan else AscentColors.Mist)
            Text(castText(option.check), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.75f))
        }
        costLine(option.skill, option.mpCost)?.let { Text(it, style = AscentTextStyles.Number, color = AscentColors.Mist) }
    }
}

private val previewCollapse = SkillDef(
    "eigenstate_collapse", "Eigenstate Collapse", SkillTree.MIND, 4, SkillKind.ACTIVE,
    "3× XP on this quest; the Quests tab shows only it until done or the day ends.",
    mp = 80, limit = SkillLimit(LimitPeriod.WEEK), handler = "eigenstate_collapse",
)
private val previewLimitBreak = SkillDef(
    "limit_break", "Limit Break", SkillTree.BODY, 3, SkillKind.ACTIVE,
    "The largest target the weekly cap allows, and 3× XP.",
    mp = 40, limit = SkillLimit(LimitPeriod.WEEK), handler = "limit_break",
)

@Preview(name = "Cast picker", widthDp = 400, heightDp = 360)
@Composable
private fun CastPickerPreview() {
    AscentTheme {
        SystemBackground {
            HoloPanel(Modifier.padding(16.dp)) {
                CastPicker(
                    "Push-ups 40 reps",
                    listOf(
                        CastOption(previewCollapse, CastCheck.Ok, 80),
                        CastOption(previewLimitBreak, CastCheck.NotEnoughMp(40, 25), 40, newTarget = 44.0),
                    ),
                    onCast = { _, _ -> },
                )
            }
        }
    }
}
