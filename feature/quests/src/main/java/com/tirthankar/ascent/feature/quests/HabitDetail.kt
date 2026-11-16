package com.tirthankar.ascent.feature.quests

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.HoloPanel
import com.tirthankar.ascent.designsystem.SystemBackground
import com.tirthankar.ascent.engine.HabitStrength
import com.tirthankar.ascent.engine.SeriesPeriod
import com.tirthankar.ascent.engine.StrengthLabel
import com.tirthankar.ascent.engine.StrengthObservation
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The habit-strength detail opened from a quest card (PHASE5.md section 1). */
@Composable
internal fun HabitDetailDialog(habit: HabitDetailUi, onEvent: (QuestLogEvent) -> Unit) {
    Dialog(onDismissRequest = { onEvent(QuestLogEvent.CloseHabit) }) {
        HabitDetailPanel(habit, onClose = { onEvent(QuestLogEvent.CloseHabit) })
    }
}

@Composable
internal fun HabitDetailPanel(habit: HabitDetailUi, onClose: () -> Unit, modifier: Modifier = Modifier) {
    HoloPanel(modifier.fillMaxWidth(), header = stringResource(R.string.habit_header).uppercase()) {
        Text(habit.title, style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                stringResource(R.string.habit_percent, habit.percent),
                style = AscentTextStyles.Number.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize),
                color = habit.label.color(),
            )
            Text(
                stringResource(habit.label.labelRes()).uppercase(),
                style = AscentTextStyles.SystemLabel,
                color = habit.label.color(),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (habit.trajectory.isEmpty()) {
            Text(stringResource(R.string.habit_none), style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.7f))
        } else {
            Sparkline(habit.trajectory, habit.percent, Modifier.padding(top = 12.dp))
        }
        Text(
            pluralStringResource(
                if (habit.weekly) R.plurals.habit_streak_weeks else R.plurals.habit_streak_days,
                habit.streak,
                habit.streak,
                habit.best,
            ),
            style = AscentTextStyles.Number,
            color = AscentColors.Mist.copy(alpha = 0.9f),
            modifier = Modifier.padding(top = 12.dp),
        )
        habit.measuringSince?.let { since ->
            val locale = LocalConfiguration.current.locales[0]
            val date = since.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            Text(
                stringResource(R.string.habit_measuring_since, date),
                style = MaterialTheme.typography.bodySmall,
                color = AscentColors.GlowCyan.copy(alpha = 0.8f),
            )
        }
        Text(
            stringResource(R.string.habit_explain),
            style = MaterialTheme.typography.bodySmall,
            color = AscentColors.Mist.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.habit_close), color = AscentColors.GlowCyan)
            }
        }
    }
}

/** Strength over the last periods, 0 at the bottom and 100% at the top, with the 85% line. */
@Composable
private fun Sparkline(values: List<Double>, percent: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.habit_sparkline_description, values.size, percent)
    val line = AscentColors.GlowCyan
    val guide = AscentColors.Mist.copy(alpha = 0.25f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics { contentDescription = description },
    ) {
        val bound = size.height * (1f - BOUND_LINE)
        drawLine(guide, Offset(0f, bound), Offset(size.width, bound), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawLine(guide, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        if (values.size == 1) {
            drawCircle(line, radius = 3.dp.toPx(), center = Offset(size.width, size.height * (1f - values[0].toFloat())))
            return@Canvas
        }
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val point = Offset(i * step, size.height * (1f - v.toFloat().coerceIn(0f, 1f)))
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The 85% "Bound" threshold, drawn as a guide. */
private const val BOUND_LINE = 0.85f

@StringRes
internal fun StrengthLabel.labelRes(): Int = when (this) {
    StrengthLabel.UNSTABLE -> R.string.habit_label_unstable
    StrengthLabel.FORMING -> R.string.habit_label_forming
    StrengthLabel.HOLDING -> R.string.habit_label_holding
    StrengthLabel.BOUND -> R.string.habit_label_bound
}

internal fun StrengthLabel.color() = when (this) {
    StrengthLabel.UNSTABLE -> AscentColors.PenaltyRed
    StrengthLabel.FORMING -> AscentColors.LegendGold
    StrengthLabel.HOLDING -> AscentColors.SystemBlue
    StrengthLabel.BOUND -> AscentColors.GlowCyan
}

// ---- Previews ----

internal object SampleHabits {
    /** A daily habit: 60 good days, a lapse, and a climb back. */
    val holding: HabitDetailUi = run {
        val history = (0L until 90L).map { i -> StrengthObservation(i, if (i in 58L..62L || i % 11 == 0L) 0.0 else 1.0) }
        val summary = HabitStrength.summarize(history, SeriesPeriod.DAY)
        HabitDetailUi(
            "Push-ups", summary.percent, summary.label, summary.streak, summary.bestStreak,
            weekly = false, measuringSince = null, trajectory = HabitStrength.trajectory(history, SeriesPeriod.DAY),
        )
    }

    val measuring = HabitDetailUi(
        "Stretch",
        23,
        StrengthLabel.UNSTABLE,
        streak = 4,
        best = 4,
        weekly = false,
        measuringSince = LocalDate.of(2026, 11, 12),
        trajectory = listOf(0.052, 0.102, 0.149, 0.193, 0.235),
    )
}

@Preview(name = "Habit strength — holding", widthDp = 400, heightDp = 520)
@Composable
private fun HabitHoldingPreview() {
    AscentTheme { SystemBackground { Column(Modifier.padding(16.dp)) { HabitDetailPanel(SampleHabits.holding, onClose = {}) } } }
}

@Preview(name = "Habit strength — measuring", widthDp = 400, heightDp = 520)
@Composable
private fun HabitMeasuringPreview() {
    AscentTheme { SystemBackground { Column(Modifier.padding(16.dp)) { HabitDetailPanel(SampleHabits.measuring, onClose = {}) } } }
}
