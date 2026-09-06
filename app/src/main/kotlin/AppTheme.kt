package net.rokoucha.visiomata

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import net.rokoucha.visiomata.theme.VisiomataTheme

private const val TvUiScale = 0.85f

@Composable
fun AppTheme(
    isTv: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (isTv) {
        val density = LocalDensity.current
        // Home cards share regular Material 3 building blocks with handheld UI.
        // Provide both theme families so those components resolve the same dark palette.
        VisiomataTheme(darkTheme = true, dynamicColor = false) {
            val mobileColors = androidx.compose.material3.MaterialTheme.colorScheme
            androidx.tv.material3.MaterialTheme(
                colorScheme =
                    androidx.tv.material3.darkColorScheme(
                        primary = mobileColors.primary,
                        onPrimary = mobileColors.onPrimary,
                        primaryContainer = mobileColors.primaryContainer,
                        onPrimaryContainer = mobileColors.onPrimaryContainer,
                        inversePrimary = mobileColors.inversePrimary,
                        secondary = mobileColors.secondary,
                        onSecondary = mobileColors.onSecondary,
                        secondaryContainer = mobileColors.secondaryContainer,
                        onSecondaryContainer = mobileColors.onSecondaryContainer,
                        tertiary = mobileColors.tertiary,
                        onTertiary = mobileColors.onTertiary,
                        tertiaryContainer = mobileColors.tertiaryContainer,
                        onTertiaryContainer = mobileColors.onTertiaryContainer,
                        background = mobileColors.background,
                        onBackground = mobileColors.onBackground,
                        surface = mobileColors.surface,
                        onSurface = mobileColors.onSurface,
                        surfaceVariant = mobileColors.surfaceVariant,
                        onSurfaceVariant = mobileColors.onSurfaceVariant,
                        surfaceTint = mobileColors.surfaceTint,
                        inverseSurface = mobileColors.inverseSurface,
                        inverseOnSurface = mobileColors.inverseOnSurface,
                        error = mobileColors.error,
                        onError = mobileColors.onError,
                        errorContainer = mobileColors.errorContainer,
                        onErrorContainer = mobileColors.onErrorContainer,
                        border = mobileColors.outline,
                        borderVariant = mobileColors.outlineVariant,
                        scrim = mobileColors.scrim,
                    ),
            ) {
                androidx.tv.material3.Surface(
                    modifier = modifier.fillMaxSize(),
                    colors =
                        androidx.tv.material3.SurfaceDefaults.colors(
                            containerColor = androidx.tv.material3.MaterialTheme.colorScheme.background,
                            contentColor = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
                        ),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent,
                        contentColor = mobileColors.onBackground,
                    ) {
                        CompositionLocalProvider(
                            LocalDensity provides
                                Density(
                                    density = density.density * TvUiScale,
                                    fontScale = density.fontScale,
                                ),
                            content = content,
                        )
                    }
                }
            }
        }
    } else {
        VisiomataTheme {
            Surface(
                modifier = modifier.fillMaxSize(),
                color = Color.Transparent,
                content = content,
            )
        }
    }
}
