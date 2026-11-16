package com.tirthankar.ascent.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A System window: translucent glass, a 1 dp glowing border, corner brackets,
 * a 4% scanline overlay and an optional "◆ HEADER" (SPEC "Visual and audio identity").
 *
 * [header] comes from strings.xml in sentence case; the panel uppercases it.
 */
@Composable
fun HoloPanel(
    modifier: Modifier = Modifier,
    header: String? = null,
    accent: Color = AscentColors.SystemBlue,
    glow: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier, propagateMinConstraints = true) {
        if (glow) HoloGlow(modifier = Modifier.matchParentSize(), color = accent.copy(alpha = 0.5f))
        Column(
            modifier = Modifier
                .background(AscentColors.Panel)
                .border(1.dp, accent.copy(alpha = 0.7f))
                .scanlines()
                .cornerBrackets(accent)
                .padding(16.dp),
        ) {
            if (header != null) {
                SystemHeader(text = header, color = accent)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** "◆ HEADER" with a faint rule underneath, marked as a heading for TalkBack. */
@Composable
fun SystemHeader(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AscentColors.SystemBlue,
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) { heading() }) {
        Text(
            text = "◆ " + text.uppercase(),
            style = AscentTextStyles.SystemHeader,
            color = color,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color.copy(alpha = 0.3f)),
        )
    }
}

/** L-shaped brackets at the four corners, drawn over the border. */
private fun Modifier.cornerBrackets(
    color: Color,
    length: Dp = 12.dp,
    thickness: Dp = 2.dp,
): Modifier = drawWithContent {
    drawContent()
    val l = length.toPx()
    val t = thickness.toPx()
    val w = size.width
    val h = size.height
    val half = t / 2
    // Top-left, top-right, bottom-left, bottom-right.
    listOf(
        Offset(half, half) to Offset(1f, 1f),
        Offset(w - half, half) to Offset(-1f, 1f),
        Offset(half, h - half) to Offset(1f, -1f),
        Offset(w - half, h - half) to Offset(-1f, -1f),
    ).forEach { (corner, dir) ->
        drawLine(color, corner, Offset(corner.x + dir.x * l, corner.y), strokeWidth = t)
        drawLine(color, corner, Offset(corner.x, corner.y + dir.y * l), strokeWidth = t)
    }
}
