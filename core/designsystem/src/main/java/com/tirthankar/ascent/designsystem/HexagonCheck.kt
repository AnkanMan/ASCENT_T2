package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Quest Log's check-off: a hexagon that fills and strokes a check in (SPEC
 * "Quest complete"). 48 dp touch target; TalkBack reads [contentDescription] and the
 * checked state. Instant with reduced motion.
 */
@Composable
fun HexagonCheck(
    checked: Boolean,
    onToggle: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = AscentColors.GlowCyan,
) {
    val reduced = LocalReducedMotion.current
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(320, easing = FastOutSlowInEasing),
        label = "check",
    )
    val tint = if (enabled) accent else accent.copy(alpha = 0.4f)
    val checkSegment = remember { Path() }
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val r = min(size.width, size.height) / 2f
            val hex = Path().apply {
                for (i in 0 until 6) {
                    val a = PI / 180.0 * (60 * i - 90)
                    val p = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(hex, tint.copy(alpha = 0.25f * progress))
            drawPath(hex, tint, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))

            if (progress > 0f) {
                val check = Path().apply {
                    moveTo(center.x - r * 0.42f, center.y + r * 0.02f)
                    lineTo(center.x - r * 0.10f, center.y + r * 0.34f)
                    lineTo(center.x + r * 0.45f, center.y - r * 0.30f)
                }
                val measure = PathMeasure().apply { setPath(check, false) }
                checkSegment.reset()
                measure.getSegment(0f, measure.length * progress, checkSegment, true)
                drawPath(
                    checkSegment,
                    AscentColors.Mist,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
