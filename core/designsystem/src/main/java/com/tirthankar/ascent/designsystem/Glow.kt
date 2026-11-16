package com.tirthankar.ascent.designsystem

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * A soft glow around the bounds it's given. Place it behind a panel with
 * Modifier.matchParentSize(). Uses a RenderEffect blur on Android 12+ and
 * drawn concentric strokes below that (SPEC).
 */
@Composable
fun HoloGlow(
    modifier: Modifier = Modifier,
    color: Color = AscentColors.SystemBlue,
    radius: Dp = 10.dp,
) {
    val glowModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier
            .graphicsLayer {
                val blur = radius.toPx() / 2f
                renderEffect = BlurEffect(blur, blur, TileMode.Decal)
                clip = false
            }
            .drawBehind {
                val inset = radius.toPx()
                drawRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
    } else {
        Modifier.drawBehind {
            val inset = radius.toPx()
            val rings = 4
            val step = inset / rings
            for (i in 1..rings) {
                val outward = i * step
                drawRect(
                    color = color.copy(alpha = 0.22f * (1f - i / (rings + 1f))),
                    topLeft = Offset(inset - outward, inset - outward),
                    size = Size(size.width - 2 * (inset - outward), size.height - 2 * (inset - outward)),
                    style = Stroke(width = step),
                )
            }
        }
    }
    Spacer(modifier.expandBy(radius).then(glowModifier))
}

/** Lays the content out [amount] larger on every side without changing its reported size. */
private fun Modifier.expandBy(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
    layout(placeable.width - 2 * px, placeable.height - 2 * px) {
        placeable.place(-px, -px)
    }
}
