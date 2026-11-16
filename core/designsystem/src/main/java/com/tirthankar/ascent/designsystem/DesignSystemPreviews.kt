package com.tirthankar.ascent.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Previews for the design-system tokens and components. Text here is sample content.

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    AscentTheme {
        SystemBackground(Modifier.padding(0.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@Preview(name = "Tokens", widthDp = 360)
@Composable
private fun TokenSwatchPreview() = PreviewFrame {
    listOf(
        "Void" to AscentColors.Void,
        "Panel" to AscentColors.Panel,
        "System Blue" to AscentColors.SystemBlue,
        "Glow Cyan" to AscentColors.GlowCyan,
        "Arcane Violet" to AscentColors.ArcaneViolet,
        "Legend Gold" to AscentColors.LegendGold,
        "Penalty Red" to AscentColors.PenaltyRed,
        "Mist" to AscentColors.Mist,
    ).forEach { (name, color) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(color))
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
        }
    }
}

@Preview(name = "Type specimen", widthDp = 360)
@Composable
private fun TypeSpecimenPreview() = PreviewFrame {
    Text("Status window", style = MaterialTheme.typography.headlineLarge, color = AscentColors.Mist)
    Text("Daily quest", style = MaterialTheme.typography.titleLarge, color = AscentColors.Mist)
    Text("◆ QUEST ISSUED", style = AscentTextStyles.SystemHeader, color = AscentColors.SystemBlue)
    Text("The Body Gate stirs. Four objectives. Expires 23:59.", style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
    Text("Lv 24 · 1,309 / 1,995 XP", style = AscentTextStyles.Number, color = AscentColors.GlowCyan)
    Text("24", style = AscentTextStyles.NumberLarge, color = AscentColors.Mist)
}

@Preview(name = "HoloPanel", widthDp = 360)
@Composable
private fun HoloPanelPreview() = PreviewFrame {
    HoloPanel(Modifier.fillMaxWidth(), header = "Quest issued") {
        Text("Push-ups 0 / 40", style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
    }
    HoloPanel(Modifier.fillMaxWidth()) {
        Text("A panel without a header.", style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist)
    }
    HoloPanel(Modifier.fillMaxWidth(), header = "Warning", accent = AscentColors.PenaltyRed) {
        Text(
            "Daily objectives incomplete. A penalty trial has been prepared. Long content wraps " +
                "inside the panel and the corner brackets stay at the corners.",
            style = MaterialTheme.typography.bodyMedium,
            color = AscentColors.Mist,
        )
    }
}

@Preview(name = "Glow, API 31+", widthDp = 360, apiLevel = 35)
@Composable
private fun GlowBlurPreview() = PreviewFrame {
    HoloPanel(Modifier.fillMaxWidth(), header = "Blur glow") { Spacer(Modifier.height(40.dp)) }
}

@Preview(name = "Glow, API 30 fallback", widthDp = 360, apiLevel = 30)
@Composable
private fun GlowFallbackPreview() = PreviewFrame {
    HoloPanel(Modifier.fillMaxWidth(), header = "Drawn glow") { Spacer(Modifier.height(40.dp)) }
}

@Preview(name = "Materialize (use interactive mode)", widthDp = 360)
@Composable
private fun MaterializePreview() = PreviewFrame {
    Materialize {
        HoloPanel(Modifier.fillMaxWidth(), header = "System notification") {
            Text("An excitation has been detected.", style = MaterialTheme.typography.bodyLarge, color = AscentColors.Mist)
        }
    }
}

@Preview(name = "Progress bars", widthDp = 360)
@Composable
private fun ProgressBarsPreview() = PreviewFrame {
    listOf(0L, 50L, 100L).forEach { value ->
        SystemProgressBar("XP", current = value * 20, max = 2_000, kind = SystemBarKind.XP)
        SystemProgressBar("HP", current = value, max = 100, kind = SystemBarKind.HP)
        SystemProgressBar("MP", current = value, max = 100, kind = SystemBarKind.MP)
    }
}
