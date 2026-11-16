package com.tirthankar.ascent.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One axis of the radar: a short label such as "STR" and its value. */
data class RadarAxis(val label: String, val value: Int)

/**
 * The six-stat hexagon radar (SPEC Status Window). Scales to the largest value, with
 * at least [minScale] at the rim so early stats don't fill the chart.
 * [contentDescription] should list every value for TalkBack.
 */
@Composable
fun StatRadar(
    axes: List<RadarAxis>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    minScale: Int = 20,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = AscentTextStyles.SystemLabel.copy(color = AscentColors.SystemBlue)
    Canvas(
        modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        if (axes.isEmpty()) return@Canvas
        val labelRoom = 28.dp.toPx()
        val r = min(size.width, size.height) / 2f - labelRoom
        val scale = max(minScale, axes.maxOf { it.value }).toFloat()
        fun point(i: Int, fraction: Float): Offset {
            val a = PI / 180.0 * (360.0 / axes.size * i - 90)
            return Offset(center.x + r * fraction * cos(a).toFloat(), center.y + r * fraction * sin(a).toFloat())
        }
        fun polygon(fraction: (Int) -> Float) = Path().apply {
            axes.indices.forEach { i ->
                val p = point(i, fraction(i))
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }

        val grid = AscentColors.SystemBlue.copy(alpha = 0.25f)
        listOf(1f / 3, 2f / 3, 1f).forEach { ring -> drawPath(polygon { ring }, grid, style = Stroke(1.dp.toPx())) }
        axes.indices.forEach { i -> drawLine(grid, center, point(i, 1f), strokeWidth = 1.dp.toPx()) }

        val shape = polygon { i -> (axes[i].value / scale).coerceIn(0f, 1f) }
        drawPath(shape, AscentColors.GlowCyan.copy(alpha = 0.22f))
        drawPath(shape, AscentColors.GlowCyan, style = Stroke(2.dp.toPx()))

        axes.forEachIndexed { i, axis ->
            val layout = measurer.measure("${axis.label} ${axis.value}", labelStyle)
            val anchor = point(i, 1f + labelRoom / 2f / r)
            drawText(
                layout,
                topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y - layout.size.height / 2f),
            )
        }
    }
}
