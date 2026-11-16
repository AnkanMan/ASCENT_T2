package com.tirthankar.ascent.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Full-screen Void background with the faint hex grid. Screens sit inside this. */
@Composable
fun SystemBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .background(AscentColors.Void)
            .hexGrid(),
        content = content,
    )
}

/**
 * Draws a pointy-top hex grid behind the content. The path is built once per size
 * (drawWithCache), so it costs one drawPath per frame.
 */
fun Modifier.hexGrid(
    color: Color = AscentColors.SystemBlue.copy(alpha = 0.06f),
    cellRadius: Dp = 16.dp,
): Modifier = drawWithCache {
    val r = cellRadius.toPx()
    val w = sqrt(3f) * r
    val rowStep = 1.5f * r
    val path = Path()
    var row = 0
    var cy = 0f
    while (cy - r < size.height) {
        var cx = if (row % 2 == 0) 0f else w / 2f
        while (cx - w / 2f < size.width) {
            addHexagon(path, Offset(cx, cy), r)
            cx += w
        }
        cy += rowStep
        row++
    }
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawBehind { drawPath(path, color, style = stroke) }
}

private fun addHexagon(path: Path, center: Offset, r: Float) {
    for (i in 0..5) {
        val angle = PI / 180.0 * (60 * i - 90)
        val x = center.x + r * cos(angle).toFloat()
        val y = center.y + r * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** The 4% scanline overlay drawn on top of panel content. */
fun Modifier.scanlines(
    color: Color = AscentColors.Mist.copy(alpha = 0.04f),
    spacing: Dp = 3.dp,
): Modifier = drawWithCache {
    val step = spacing.toPx()
    val path = Path()
    var y = 0f
    while (y < size.height) {
        path.moveTo(0f, y)
        path.lineTo(size.width, y)
        y += step
    }
    val stroke = Stroke(width = 1f)
    onDrawWithContent {
        drawContent()
        drawPath(path, color, style = stroke)
    }
}
