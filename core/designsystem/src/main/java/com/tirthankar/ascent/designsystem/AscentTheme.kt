package com.tirthankar.ascent.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val AscentColorScheme = darkColorScheme(
    primary = AscentColors.SystemBlue,
    onPrimary = AscentColors.Void,
    primaryContainer = AscentColors.PanelSolid,
    onPrimaryContainer = AscentColors.Mist,
    secondary = AscentColors.GlowCyan,
    onSecondary = AscentColors.Void,
    tertiary = AscentColors.ArcaneViolet,
    onTertiary = AscentColors.Void,
    background = AscentColors.Void,
    onBackground = AscentColors.Mist,
    surface = AscentColors.Void,
    onSurface = AscentColors.Mist,
    surfaceVariant = AscentColors.PanelSolid,
    onSurfaceVariant = AscentColors.Mist,
    error = AscentColors.PenaltyRed,
    onError = AscentColors.Void,
    outline = AscentColors.SystemBlue,
)

/** The one ASCENT theme: always dark, always the System palette. */
@Composable
fun AscentTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides rememberSystemReducedMotion()) {
        MaterialTheme(
            colorScheme = AscentColorScheme,
            typography = AscentTypography,
            content = content,
        )
    }
}
