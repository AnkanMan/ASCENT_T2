@file:OptIn(ExperimentalTextApi::class)

package com.tirthankar.ascent.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Headers and System window titles. */
val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

private fun variable(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Body text. */
val Inter = FontFamily(
    variable(R.font.inter_variable, 400),
    variable(R.font.inter_variable, 500),
    variable(R.font.inter_variable, 600),
)

/** Numbers and stats. */
val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono_variable, 400),
    variable(R.font.jetbrains_mono_variable, 500),
    variable(R.font.jetbrains_mono_variable, 700),
)

/** Material roles: display/headline/title use Rajdhani, body/label use Inter. All sizes in sp. */
val AscentTypography = Typography(
    displayLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp),
    displaySmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp),
    titleSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** Styles outside the Material roles. */
object AscentTextStyles {
    /** System window headers, e.g. "◆ QUEST ISSUED". Callers pass uppercase text. */
    val SystemHeader = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 2.sp)

    /** Small label above a bar or stat, e.g. "HP". */
    val SystemLabel = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 1.5.sp)

    /** Numbers and stat values. */
    val Number = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)

    /** Hero numbers, e.g. the level. */
    val NumberLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp)
}
