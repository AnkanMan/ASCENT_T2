package com.tirthankar.ascent.feature.status

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.AvatarFigure
import com.tirthankar.ascent.designsystem.AvatarSlot
import com.tirthankar.ascent.designsystem.AvatarSpec
import com.tirthankar.ascent.designsystem.LayeredAvatar
import com.tirthankar.ascent.designsystem.ObjectiveProgress
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.RarityColors
import com.tirthankar.ascent.designsystem.RadarAxis
import com.tirthankar.ascent.designsystem.StatRadar
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.designsystem.SystemBarKind
import com.tirthankar.ascent.designsystem.SystemProgressBar
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Stat

/** The Status Window. Stateless: renders [state] and emits [onEvent]. */
@Composable
fun StatusScreen(
    state: StatusUiState,
    modifier: Modifier = Modifier,
    onEvent: (StatusEvent) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onEvent(StatusEvent.OpenInventory) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.status_open_inventory), color = AscentColors.GlowCyan)
            }
            val codexLabel = if (state.unreadRecords > 0) {
                pluralStringResource(R.plurals.status_codex_unread, state.unreadRecords, state.unreadRecords)
            } else {
                stringResource(R.string.status_open_codex)
            }
            TextButton(
                onClick = { onEvent(StatusEvent.OpenCodex) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = codexLabel },
            ) {
                Text(stringResource(R.string.status_open_codex), color = AscentColors.GlowCyan, modifier = Modifier.clearAndSetSemantics { })
                // The unread dot (PHASE4.md section 6).
                if (state.unreadRecords > 0) Text(" ●", color = AscentColors.ArcaneViolet, modifier = Modifier.clearAndSetSemantics { })
            }
            TextButton(onClick = { onEvent(StatusEvent.OpenSettings) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.status_open_settings), color = AscentColors.GlowCyan)
            }
        }
        state.backupWarning?.let { BackupWarningLine(it, onEvent) }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.status_header)) {
            Identity(state)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SystemProgressBar(stringResource(R.string.status_xp), state.xpIntoLevel, state.xpForNext, SystemBarKind.XP)
                SystemProgressBar(stringResource(R.string.status_hp), state.hp.toLong(), state.maxHp.toLong(), SystemBarKind.HP)
                SystemProgressBar(stringResource(R.string.status_mp), state.mp.toLong(), state.maxMp.toLong(), SystemBarKind.MP)
            }
            PowersReady(state.powersReady, onEvent)
        }
        state.trial?.let { TrialPanel(it) }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.status_attributes_header)) {
            if (state.freePoints > 0 || state.pendingTotal > 0) {
                Text(
                    pluralStringResource(R.plurals.status_free_points, state.freePoints, state.freePoints),
                    style = MaterialTheme.typography.labelLarge,
                    color = AscentColors.LegendGold,
                )
                Spacer(Modifier.height(8.dp))
            }
            state.stats.forEach { line ->
                StatRow(line, canAdd = state.canAddPoint, onAdd = { onEvent(StatusEvent.AddPoint(line.stat)) })
            }
            if (state.pendingTotal > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onEvent(StatusEvent.ResetPoints) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.status_reset_points), color = AscentColors.Mist)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onEvent(StatusEvent.ConfirmPoints) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.status_confirm_points), color = AscentColors.LegendGold)
                    }
                }
            }
        }
        HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.status_radar_header)) {
            val axes = state.stats.map { RadarAxis(it.stat.name, it.value + it.pending) }
            val description = stringResource(
                R.string.status_radar_description,
                axes.joinToString(", ") { "${it.label} ${it.value}" },
            )
            StatRadar(
                axes = axes,
                contentDescription = description,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/** "Powers ready" (PHASE4.md section 9): one tap casts Overload or Unbroken; the others open Skills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowersReady(powers: List<PowerChip>, onEvent: (StatusEvent) -> Unit) {
    if (powers.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.status_powers_ready), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        powers.forEach { p ->
            val description = stringResource(if (p.oneTap) R.string.status_cast_power else R.string.status_open_power, p.name)
            OutlinedButton(
                onClick = { onEvent(if (p.oneTap) StatusEvent.CastPower(p.id) else StatusEvent.OpenSkills) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
            ) {
                Text("◈ " + p.name, color = AscentColors.GlowCyan)
            }
        }
    }
}

/** Staleness (PHASE5.md section 3): one line when the last backup is over 7 days old, or there is none. */
@Composable
private fun BackupWarningLine(warning: BackupWarning, onEvent: (StatusEvent) -> Unit) {
    TextButton(onClick = { onEvent(StatusEvent.OpenSettings) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(
            warning.daysAgo?.let { pluralStringResource(R.plurals.status_backup_stale, it, it) } ?: stringResource(R.string.status_backup_never),
            style = MaterialTheme.typography.bodyMedium,
            color = AscentColors.LegendGold,
        )
    }
}

/** The open rank trial (PHASE4.md section 4): days left, n/6, each objective. */
@Composable
private fun TrialPanel(trial: TrialCard) {
    HoloPanel(Modifier.fillMaxWidth(), header = stringResource(R.string.status_trial_header, trial.rank.name), accent = AscentColors.LegendGold) {
        Text(
            stringResource(R.string.status_trial_line, trial.daysLeft, trial.done, trial.total),
            style = AscentTextStyles.Number,
            color = AscentColors.Mist,
        )
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            trial.objectives.forEach { o ->
                ObjectiveProgress(
                    title = o.title,
                    valueText = "${trialNumber(o.progress)}/${trialNumber(o.target)} ${o.unit}",
                    fraction = if (o.target > 0) (o.progress / o.target).toFloat().coerceIn(0f, 1f) else 0f,
                )
            }
        }
    }
}

private fun trialNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun Identity(state: StatusUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val avatarDescription = pluralStringResource(R.plurals.status_avatar_description, state.gear.size, state.gear.size)
        val avatar = state.avatar
        if (avatar != null) {
            LayeredAvatar(avatar, avatarDescription, Modifier.width(72.dp).height(104.dp))
        } else {
            AvatarFigure(
                gear = state.gear.mapValues { RarityColors.forLevel(it.value) },
                aura = state.rank.auraColor(),
                mythicAura = state.mythicAura,
                contentDescription = avatarDescription,
                modifier = Modifier
                    .width(72.dp)
                    .height(104.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(state.name, style = MaterialTheme.typography.headlineMedium, color = AscentColors.Mist)
            Text(state.title, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist.copy(alpha = 0.7f))
            // The class line under the name (the title can be another one).
            state.className?.let { name ->
                val line = if (state.classStats.isEmpty()) name else stringResource(R.string.status_class_line, name, state.classStats.joinToString("/") { it.name })
                Text(line, style = AscentTextStyles.SystemLabel, color = AscentColors.ArcaneViolet)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(R.plurals.status_streak, state.streakDays, state.streakDays),
                style = MaterialTheme.typography.labelMedium,
                color = AscentColors.GlowCyan,
            )
        }
        RankAndLevel(state.rank, state.level)
    }
}

@Composable
private fun RankAndLevel(rank: Rank, level: Int) {
    val rankText = rankLabel(rank)
    val description = stringResource(R.string.status_rank_level_description, rankText, level)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelledValue(R.string.status_rank, rankText, Modifier.border(1.dp, AscentColors.SystemBlue).padding(horizontal = 10.dp, vertical = 4.dp))
        Spacer(Modifier.width(12.dp))
        LabelledValue(R.string.status_level, level.toString())
    }
}

@Composable
private fun LabelledValue(@StringRes label: Int, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(label).uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
        Text(value, style = AscentTextStyles.NumberLarge, color = AscentColors.Mist)
    }
}

@Composable
private fun rankLabel(rank: Rank): String =
    if (rank == Rank.SIGMA) stringResource(R.string.status_rank_sigma) else rank.name

/** Aura colour by rank (SPEC "Ranks"). */
private fun Rank.auraColor(): Color = when (this) {
    Rank.E -> AscentColors.Mist.copy(alpha = 0.5f)
    Rank.D -> AscentColors.SystemBlue
    Rank.C -> AscentColors.GlowCyan
    Rank.B -> AscentColors.ArcaneViolet
    Rank.A -> AscentColors.LegendGold
    Rank.S -> AscentColors.Mist
    Rank.SIGMA -> AscentColors.LegendGold
}

@Composable
private fun StatRow(line: StatLine, canAdd: Boolean, onAdd: () -> Unit) {
    val name = stringResource(line.stat.nameRes())
    val description = stringResource(R.string.status_stat_description, name, line.value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(56.dp)) {
                Text(line.stat.name, style = AscentTextStyles.SystemLabel, color = AscentColors.SystemBlue)
            }
            Text(name, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist, modifier = Modifier.weight(1f))
            Text(line.value.toString(), style = AscentTextStyles.Number, color = AscentColors.Mist)
            if (line.pending > 0) {
                Text(
                    stringResource(R.string.status_pending_points, line.pending),
                    style = AscentTextStyles.Number,
                    color = AscentColors.LegendGold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (canAdd) {
            val addDescription = stringResource(R.string.status_add_point_description, name)
            IconButton(
                onClick = onAdd,
                modifier = Modifier.semantics { contentDescription = addDescription },
            ) {
                Text(
                    "+",
                    style = AscentTextStyles.NumberLarge.copy(fontSize = AscentTextStyles.Number.fontSize * 1.4f),
                    color = AscentColors.LegendGold,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

@StringRes
private fun Stat.nameRes(): Int = when (this) {
    Stat.STR -> R.string.stat_str
    Stat.AGI -> R.string.stat_agi
    Stat.INT -> R.string.stat_int
    Stat.FOC -> R.string.stat_foc
    Stat.WIS -> R.string.stat_wis
    Stat.VIT -> R.string.stat_vit
}

@Preview(name = "Status Window", widthDp = 400, heightDp = 1200)
@Composable
private fun StatusScreenPreview() {
    AscentTheme {
        SystemBackground {
            StatusScreen(StatusUiStateMapper.map(SamplePlayer.snapshot))
        }
    }
}

@Preview(name = "Status Window — geared", widthDp = 400, heightDp = 1200)
@Composable
private fun StatusScreenGearPreview() {
    AscentTheme {
        SystemBackground {
            StatusScreen(
                StatusUiStateMapper.map(SamplePlayer.snapshot).copy(
                    gear = mapOf(AvatarSlot.HEAD to 3, AvatarSlot.BODY to 2, AvatarSlot.WEAPON to 4, AvatarSlot.FEET to 1),
                ),
            )
        }
    }
}

@Preview(name = "Status Window — Phase 4", widthDp = 400, heightDp = 1500)
@Composable
private fun StatusScreenPhase4Preview() {
    AscentTheme {
        SystemBackground {
            StatusScreen(
                StatusUiStateMapper.map(SamplePlayer.snapshot.copy(rank = Rank.D, level = 12)).copy(
                    avatar = AvatarSpec("SYSTEM_BLUE", 14, 1, "long_split", "circle_bisected"),
                    className = "Theorist",
                    classStats = listOf(Stat.INT, Stat.WIS),
                    powersReady = listOf(PowerChip("overload", "Overload", true), PowerChip("insight", "Insight", false)),
                    trial = TrialCard(
                        Rank.C, 4, 3, 6,
                        listOf(
                            TrialObjectiveLine("Deep-work minutes", 300.0, 480.0, "min"),
                            TrialObjectiveLine("Steps", 42000.0, 42000.0, "steps"),
                        ),
                    ),
                    unreadRecords = 2,
                    backupWarning = BackupWarning(daysAgo = 9),
                ),
            )
        }
    }
}

@Preview(name = "Status Window — points to allocate", widthDp = 400, heightDp = 1200)
@Composable
private fun StatusScreenPointsPreview() {
    AscentTheme {
        SystemBackground {
            StatusScreen(
                StatusUiStateMapper.map(
                    SamplePlayer.snapshot.copy(freePoints = 3),
                    pending = mapOf(Stat.STR to 1),
                ),
            )
        }
    }
}
