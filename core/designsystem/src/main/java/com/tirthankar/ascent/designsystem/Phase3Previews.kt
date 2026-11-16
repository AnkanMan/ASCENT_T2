package com.tirthankar.ascent.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Previews show the final frame: reduced motion skips the animations.

@Preview(name = "Chest reveal", widthDp = 400, heightDp = 700)
@Composable
private fun ChestRevealPreview() {
    AscentTheme {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            SystemBackground {
                ChestRevealOverlay(
                    header = "Chest opened",
                    items = listOf(
                        ChestItem("Hamiltonian Blade", "Legendary · weapon", RarityColors.Legendary),
                        ChestItem("Ergodic Cowl", "Rare · head", RarityColors.Rare),
                    ),
                    dismissLabel = "Continue",
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "System notice", widthDp = 400, heightDp = 600)
@Composable
private fun NoticePreview() {
    AscentTheme {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            SystemBackground {
                SystemNoticeOverlay(
                    header = "Hidden condition met",
                    subject = "Still Night",
                    detail = "Fourteen nights at the mark. In the quiet, you were the one taking measurements.",
                    accent = AscentColors.ArcaneViolet,
                    dismissLabel = "Continue",
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "Boss HP and avatars", widthDp = 400, heightDp = 420)
@Composable
private fun BossAndAvatarPreview() {
    AscentTheme {
        SystemBackground {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BossHpBar("HP", hp = 2_600, maxHp = 4_000, phaseMarkers = listOf(0.66f, 0.33f), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AvatarFigure(emptyMap(), AscentColors.Mist.copy(alpha = 0.5f), false, "Avatar", Modifier.width(96.dp).height(140.dp))
                    AvatarFigure(
                        mapOf(AvatarSlot.HEAD to RarityColors.Epic, AvatarSlot.BODY to RarityColors.Rare, AvatarSlot.WEAPON to RarityColors.Legendary, AvatarSlot.FEET to RarityColors.Common, AvatarSlot.HANDS to RarityColors.Epic),
                        AscentColors.GlowCyan,
                        false,
                        "Avatar",
                        Modifier.width(96.dp).height(140.dp),
                    )
                    AvatarFigure(mapOf(AvatarSlot.HEAD to RarityColors.Mythic), AscentColors.LegendGold, true, "Avatar", Modifier.width(96.dp).height(140.dp))
                }
            }
        }
    }
}
