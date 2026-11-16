package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/** SPEC: about 220 ms from a thin line to full height. */
const val MATERIALIZE_DURATION_MS = 220

private const val LINE_SCALE = 0.01f

/**
 * Window materialize: the content grows from a thin horizontal line to full height
 * in [MATERIALIZE_DURATION_MS] with two brightness flickers. With "Remove animations"
 * on, it appears at once.
 */
@Composable
fun Materialize(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val scaleY = remember { Animatable(if (reducedMotion) 1f else LINE_SCALE) }
    val alpha = remember { Animatable(if (reducedMotion) 1f else 0f) }

    // Runs once on enter; a later settings change doesn't replay it.
    LaunchedEffect(Unit) {
        if (reducedMotion) return@LaunchedEffect
        launch {
            scaleY.animateTo(1f, tween(MATERIALIZE_DURATION_MS, easing = FastOutSlowInEasing))
        }
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = keyframes {
                durationMillis = MATERIALIZE_DURATION_MS
                0f at 0
                1f at 50
                0.35f at 80 // flicker 1
                1f at 120
                0.5f at 160 // flicker 2
                1f at MATERIALIZE_DURATION_MS
            },
        )
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.scaleY = scaleY.value
            this.alpha = alpha.value
            transformOrigin = TransformOrigin.Center
        },
    ) {
        content()
    }
}
