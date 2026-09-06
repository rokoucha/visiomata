package net.rokoucha.visiomata.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val VisioFontFamily = FontFamily.SansSerif

val Typography =
    Typography(
        displayLarge = expressiveText(57, 64, FontWeight.Bold, -0.25f),
        displayMedium = expressiveText(45, 52, FontWeight.Bold),
        displaySmall = expressiveText(36, 44, FontWeight.Bold),
        headlineLarge = expressiveText(32, 40, FontWeight.Bold),
        headlineMedium = expressiveText(28, 36, FontWeight.SemiBold),
        headlineSmall = expressiveText(24, 32, FontWeight.SemiBold),
        titleLarge = expressiveText(22, 28, FontWeight.SemiBold),
        titleMedium = expressiveText(16, 24, FontWeight.SemiBold, 0.15f),
        titleSmall = expressiveText(14, 20, FontWeight.SemiBold, 0.1f),
        bodyLarge = expressiveText(16, 24, FontWeight.Normal, 0.5f),
        bodyMedium = expressiveText(14, 20, FontWeight.Normal, 0.25f),
        bodySmall = expressiveText(12, 16, FontWeight.Normal, 0.4f),
        labelLarge = expressiveText(14, 20, FontWeight.SemiBold, 0.1f),
        labelMedium = expressiveText(12, 16, FontWeight.SemiBold, 0.5f),
        labelSmall = expressiveText(11, 16, FontWeight.SemiBold, 0.5f),
    )

private fun expressiveText(
    fontSize: Int,
    lineHeight: Int,
    fontWeight: FontWeight,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = VisioFontFamily,
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)
