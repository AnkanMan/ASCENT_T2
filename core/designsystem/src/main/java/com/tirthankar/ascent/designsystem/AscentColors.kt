package com.tirthankar.ascent.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The SPEC palette. These are the only raw colour values in the app; feature code
 * uses these tokens (or MaterialTheme roles mapped from them), never hex.
 */
object AscentColors {
    /** App background. */
    val Void = Color(0xFF04060C)

    /** Glass panel fill, opaque. Use [Panel] for the translucent version. */
    val PanelSolid = Color(0xFF0A1424)

    /** Glass panel fill at 70% opacity. */
    val Panel = PanelSolid.copy(alpha = 0.70f)

    /** Borders, headers, primary glow; MP. */
    val SystemBlue = Color(0xFF2E8BFF)

    /** Progress bars, highlights, XP. */
    val GlowCyan = Color(0xFF00D4FF)

    /** Skills, Epic rarity. */
    val ArcaneViolet = Color(0xFF8B5CF6)

    /** Level-up, Legendary rarity. */
    val LegendGold = Color(0xFFF5B942)

    /** Warnings, penalties, boss HP; HP bar. */
    val PenaltyRed = Color(0xFFFF3B5C)

    /** Body text. */
    val Mist = Color(0xFFC9D6EA)

    // Phase 4: rank auras the core palette doesn't cover (SPEC "Ranks").

    /** E-rank aura: faint grey-blue. */
    val AuraGreyBlue = Color(0xFF7F93B2)

    /** S-rank aura: white-blue blaze. */
    val AuraBlaze = Color(0xFFE4F2FF)

    /** Σ aura: inverted black and gold (unreachable in Phase 4). */
    val AuraInverted = Color(0xFF16120A)
}

/** Rank aura colours by token name (content/avatar.json names tokens, never colours). */
object AuraPalette {
    fun forToken(token: String): Color = when (token) {
        "FAINT_GREY_BLUE" -> AscentColors.AuraGreyBlue
        "SYSTEM_BLUE" -> AscentColors.SystemBlue
        "GLOW_CYAN" -> AscentColors.GlowCyan
        "ARCANE_VIOLET" -> AscentColors.ArcaneViolet
        "LEGEND_GOLD" -> AscentColors.LegendGold
        "WHITE_BLUE_BLAZE" -> AscentColors.AuraBlaze
        "INVERTED_GOLD" -> AscentColors.LegendGold
        else -> AscentColors.AuraGreyBlue
    }
}
