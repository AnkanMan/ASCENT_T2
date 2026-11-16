package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

enum class SystemBarKind { XP, HP, MP }

private fun SystemBarKind.color(): Color = when (this) {
    SystemBarKind.XP -> AscentColors.GlowCyan
    SystemBarKind.HP -> AscentColors.PenaltyRed
    SystemBarKind.MP -> AscentColors.SystemBlue
}

/**
 * A labelled System bar: "HP ........ 180 / 250" over a glowing fill.
 * TalkBack reads it once as "[label] [current] of [max]".
 */
@Composable
fun SystemProgressBar(
    label: String,
    current: Long,
    max: Long,
    kind: SystemBarKind,
    modifier: Modifier = Modifier,
) {
    val color = kind.color()
    val fraction = if (max > 0) (current.toFloat() / max).coerceIn(0f, 1f) else 0f
    // The fill eases out to its new value (SPEC "Quest complete"); instant with reduced motion.
    val shown by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (LocalReducedMotion.current) snap() else tween(600, easing = FastOutSlowInEasing),
        label = "bar",
    )
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val currentText = numbers.format(current)
    val maxText = numbers.format(max)
    val description = stringResource(R.string.ds_progress_description, label, currentText, maxText)

    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = label.uppercase(), style = AscentTextStyles.SystemLabel, color = color)
            Text(text = "$currentText / $maxText", style = AscentTextStyles.Number, color = AscentColors.Mist)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.4f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}
