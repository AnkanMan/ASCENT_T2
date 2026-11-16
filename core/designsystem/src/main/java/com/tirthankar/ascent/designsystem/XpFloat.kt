package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * "+42 XP" floating up and fading (SPEC "Quest complete"). Restarts for each new [key].
 * With reduced motion it simply shows for a moment. TalkBack announces it once.
 */
@Composable
fun XpFloat(
    text: String,
    key: Any,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AscentColors.GlowCyan,
) {
    val reduced = LocalReducedMotion.current
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (reduced) delay(1_200) else progress.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
        onFinished()
    }
    Text(
        text = text,
        style = AscentTextStyles.Number.copy(fontSize = AscentTextStyles.NumberLarge.fontSize * 0.6f),
        color = color,
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .graphicsLayer {
                translationY = -48.dp.toPx() * progress.value
                alpha = if (reduced) 1f else 1f - 0.9f * progress.value
            },
    )
}
