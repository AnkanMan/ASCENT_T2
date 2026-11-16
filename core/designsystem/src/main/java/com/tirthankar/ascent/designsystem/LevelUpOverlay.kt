package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The level-up sequence (SPEC "Level up", D10, Compose only): the screen dims, a light
 * column rises, the level number rolls, particles burst, a gold flash, then the System
 * message. With reduced motion it shows the final frame at once.
 */
@Composable
fun LevelUpOverlay(
    fromLevel: Int,
    toLevel: Int,
    header: String,
    message: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val dim = remember { Animatable(if (reduced) 0.88f else 0f) }
    val column = remember { Animatable(if (reduced) 1f else 0f) }
    val roll = remember { Animatable(if (reduced) 1f else 0f) }
    val burst = remember { Animatable(if (reduced) 1f else 0f) }
    val flash = remember { Animatable(0f) }
    var showMessage by remember { mutableStateOf(reduced) }
    val particles = remember { List(28) { Random.nextFloat() to (0.6f + 0.4f * Random.nextFloat()) } }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        dim.animateTo(0.88f, tween(200))
        column.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        launch { roll.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        launch { burst.animateTo(1f, tween(1_000, easing = LinearOutSlowInEasing)) }
        delay(550)
        flash.animateTo(0.5f, tween(90))
        flash.animateTo(0f, tween(280))
        showMessage = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AscentColors.Void.copy(alpha = dim.value))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = showMessage,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 96.dp.toPx()
            val height = size.height * column.value
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, AscentColors.LegendGold.copy(alpha = 0.35f), AscentColors.LegendGold.copy(alpha = 0.05f)),
                    startY = size.height - height,
                    endY = size.height,
                ),
                topLeft = Offset(center.x - width / 2, size.height - height),
                size = Size(width, height),
            )
            if (burst.value in 0.001f..0.999f) {
                val maxR = size.minDimension * 0.45f
                particles.forEach { (angle, speed) ->
                    val a = angle * 2 * PI
                    val d = maxR * speed * burst.value
                    drawCircle(
                        color = AscentColors.LegendGold.copy(alpha = 1f - burst.value),
                        radius = 3.dp.toPx(),
                        center = Offset(center.x + d * cos(a).toFloat(), center.y + d * sin(a).toFloat()),
                    )
                }
            }
            drawRect(AscentColors.LegendGold.copy(alpha = flash.value))
        }

        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RollingLevel(fromLevel, toLevel, roll.value)
            if (showMessage) {
                Materialize {
                    HoloPanel(Modifier.fillMaxWidth(), header = header, accent = AscentColors.LegendGold) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AscentColors.Mist,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .heightIn(min = 48.dp)
                                .align(Alignment.End),
                        ) {
                            Text(dismissLabel, color = AscentColors.LegendGold)
                        }
                    }
                }
            }
        }
    }
}

/** The old number slides up and out as the new one slides in. */
@Composable
private fun RollingLevel(from: Int, to: Int, progress: Float) {
    val style = AscentTextStyles.NumberLarge.copy(fontSize = 72.sp)
    Box(Modifier.height(96.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        if (progress < 1f) {
            Text(
                text = from.toString(),
                style = style,
                color = AscentColors.Mist,
                modifier = Modifier.graphicsLayer {
                    translationY = -size.height * progress
                    alpha = 1f - progress
                },
            )
        }
        Text(
            text = to.toString(),
            style = style,
            color = AscentColors.LegendGold,
            modifier = Modifier.graphicsLayer {
                translationY = size.height * (1f - progress)
                alpha = progress
            },
        )
    }
}
