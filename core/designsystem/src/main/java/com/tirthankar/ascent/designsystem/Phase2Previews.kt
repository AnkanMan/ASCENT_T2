package com.tirthankar.ascent.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Previews for the Phase 2 components. Text here is sample content.

@Composable
private fun Frame(content: @Composable () -> Unit) {
    AscentTheme {
        SystemBackground {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@Preview(name = "Hexagon check", widthDp = 360)
@Composable
private fun HexagonCheckPreview() = Frame {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        var checked by remember { mutableStateOf(false) }
        HexagonCheck(checked = checked, onToggle = { checked = !checked }, contentDescription = "Complete push-ups")
        HexagonCheck(checked = true, onToggle = {}, contentDescription = "Push-ups done")
        HexagonCheck(checked = true, onToggle = {}, contentDescription = "Locked", enabled = false)
    }
}

@Preview(name = "Stat radar", widthDp = 360)
@Composable
private fun StatRadarPreview() = Frame {
    StatRadar(
        axes = listOf(
            RadarAxis("STR", 12), RadarAxis("AGI", 11), RadarAxis("INT", 15),
            RadarAxis("FOC", 13), RadarAxis("WIS", 10), RadarAxis("VIT", 9),
        ),
        contentDescription = "Attributes",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(name = "XP float", widthDp = 360)
@Composable
private fun XpFloatPreview() = Frame {
    XpFloat(text = "+92 XP", key = 1, onFinished = {})
}

@Preview(name = "Level up (final frame)", widthDp = 400, heightDp = 800)
@Composable
private fun LevelUpPreview() {
    AscentTheme {
        // Reduced motion shows the sequence's final frame, which is what a preview can capture.
        CompositionLocalProvider(LocalReducedMotion provides true) {
            SystemBackground {
                LevelUpOverlay(
                fromLevel = 9,
                toLevel = 10,
                header = "Level up",
                message = "9 → 10. 3 attribute points await allocation.",
                dismissLabel = "Continue",
                    onDismiss = {},
                )
            }
        }
    }
}
