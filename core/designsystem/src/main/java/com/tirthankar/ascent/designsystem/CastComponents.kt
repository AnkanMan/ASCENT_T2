package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cast moment (PHASE4.md section 9): a sigil ring draws itself (~600 ms), a
 * triangle locks inside it, the power's name shows, then it fades and the effect lands.
 * It never blocks touches. With reduced motion it is skipped: [onFinished] runs at once.
 */
@Composable
fun CastSigilOverlay(
    name: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AscentColors.GlowCyan,
) {
    val reduced = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinished)
    val draw = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(name) {
        if (reduced) {
            finish()
            return@LaunchedEffect
        }
        draw.animateTo(1f, tween(SIGIL_DRAW_MS, easing = FastOutSlowInEasing))
        fade.animateTo(0f, tween(SIGIL_FADE_MS, easing = LinearEasing))
        finish()
    }
    if (reduced) return
    Box(modifier.fillMaxSize().alpha(fade.value), contentAlignment = Alignment.Center) {
        SigilRing(draw.value, accent, Modifier.size(220.dp))
        Text(
            name,
            style = AscentTextStyles.SystemHeader,
            color = accent,
            modifier = Modifier
                .alpha(((draw.value - 0.5f) * 2f).coerceIn(0f, 1f))
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private const val SIGIL_DRAW_MS = 600
private const val SIGIL_FADE_MS = 260

/** The ring itself at [progress] 0..1: outer arc, tick marks, and an inner triangle. */
@Composable
fun SigilRing(progress: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2 * 0.92f
        val stroke = 2.dp.toPx()
        val sweep = 360f * progress.coerceIn(0f, 1f)
        drawArc(accent.copy(alpha = 0.25f), -90f, sweep, false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
        drawArc(accent, -90f, sweep, false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
        rotate(degrees = 60f * progress) {
            val ticks = 12
            repeat((ticks * progress).toInt()) { i ->
                val a = 2 * PI * i / ticks - PI / 2
                val inner = r * 0.82f
                drawLine(
                    accent.copy(alpha = 0.7f),
                    Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                    Offset(center.x + r * 0.9f * cos(a).toFloat(), center.y + r * 0.9f * sin(a).toFloat()),
                    strokeWidth = stroke,
                )
            }
        }
        val tri = ((progress - 0.5f) * 2f).coerceIn(0f, 1f)
        if (tri > 0f) {
            val inner = r * 0.62f
            val path = Path()
            repeat(3) { i ->
                val a = 2 * PI * i / 3 - PI / 2
                val p = Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat())
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, accent.copy(alpha = tri), style = Stroke(stroke))
        }
    }
}

/**
 * The confirm card before a cast: what it costs, what it does and what it targets.
 * [reason] replaces Confirm with the one line saying why it can't be cast.
 */
@Composable
fun CastConfirmCard(
    header: String,
    name: String,
    lines: List<String>,
    reason: String?,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** False while the cast still needs input (a name, a second quest). */
    enabled: Boolean = true,
    /** Input the power needs, shown above the buttons. */
    extra: @Composable () -> Unit = {},
) {
    val canConfirm = reason == null && enabled
    HoloPanel(modifier.fillMaxWidth(), header = header, accent = AscentColors.LegendGold) {
        Text(name, style = MaterialTheme.typography.titleLarge, color = AscentColors.LegendGold)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist, modifier = Modifier.padding(top = 4.dp)) }
        reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AscentColors.PenaltyRed, modifier = Modifier.padding(top = 8.dp)) }
        extra()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(cancelLabel, color = AscentColors.Mist)
            }
            OutlinedButton(onClick = onConfirm, enabled = canConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel, color = if (canConfirm) AscentColors.LegendGold else AscentColors.Mist.copy(alpha = 0.5f))
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Sigil ring and confirm card", widthDp = 400, heightDp = 640)
@Composable
private fun CastPreview() {
    AscentTheme {
        SystemBackground {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth().background(AscentColors.Void.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    SigilRing(1f, AscentColors.GlowCyan, Modifier.size(200.dp))
                }
                CastConfirmCard(
                    header = "Cast",
                    name = "Eigenstate Collapse",
                    lines = listOf("80 MP · once a week", "3× XP on: Derive the KPZ scaling relation", "The Quests tab shows only it until done or the day ends."),
                    reason = null,
                    confirmLabel = "Cast",
                    cancelLabel = "Cancel",
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
    }
}
