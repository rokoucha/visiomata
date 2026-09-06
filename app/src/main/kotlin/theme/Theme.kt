package net.rokoucha.visiomata.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
    darkColorScheme(
        primary = VisiomataBlueDark,
        onPrimary = Color(0xFF002E69),
        primaryContainer = VisiomataBlueContainerDark,
        onPrimaryContainer = Color(0xFFD8E2FF),
        secondary = VisiomataIndigoDark,
        onSecondary = Color(0xFF273156),
        secondaryContainer = VisiomataIndigoContainerDark,
        onSecondaryContainer = Color(0xFFDDE1FF),
        tertiary = VisiomataCoralDark,
        onTertiary = Color(0xFF541D2C),
        tertiaryContainer = VisiomataCoralContainerDark,
        onTertiaryContainer = Color(0xFFFFD9E1),
        background = Night,
        surface = NightSurface,
        surfaceContainer = Color(0xFF1D2026),
        surfaceContainerHigh = Color(0xFF282A30),
        surfaceContainerHighest = Color(0xFF33353B),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = VisiomataBlue,
        onPrimary = Color.White,
        primaryContainer = VisiomataBlueContainer,
        onPrimaryContainer = Color(0xFF001A41),
        secondary = VisiomataIndigo,
        onSecondary = Color.White,
        secondaryContainer = VisiomataIndigoContainer,
        onSecondaryContainer = Color(0xFF111A3F),
        tertiary = VisiomataCoral,
        onTertiary = Color.White,
        tertiaryContainer = VisiomataCoralContainer,
        onTertiaryContainer = Color(0xFF3A0718),
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceContainer = Color(0xFFF0F0F8),
        surfaceContainerHigh = Color(0xFFEAEAF2),
        surfaceContainerHighest = Color(0xFFE4E4EC),
    )

private val ExpressiveShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        largeIncreased = RoundedCornerShape(32.dp),
        extraLarge = RoundedCornerShape(36.dp),
        extraLargeIncreased = RoundedCornerShape(44.dp),
        extraExtraLarge = RoundedCornerShape(56.dp),
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VisiomataTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        typography = Typography,
        content = content,
    )
}
