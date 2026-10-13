package io.github.katznmeik.cinexperience.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalCinexColors = staticCompositionLocalOf { DarkCinexColors }
private val LocalCinexTextStyles = staticCompositionLocalOf { DefaultCinexTextStyles }

private fun CinexColors.toMaterialColorScheme() = darkColorScheme(
    primary = text,
    onPrimary = onPrimary,
    primaryContainer = surfaceRaised,
    onPrimaryContainer = text,
    secondary = text,
    onSecondary = onPrimary,
    secondaryContainer = surfaceRaised,
    onSecondaryContainer = text,
    tertiary = text,
    onTertiary = onPrimary,
    background = background,
    onBackground = text,
    surface = background,
    onSurface = text,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textMuted,
    surfaceContainerLowest = background,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceRaised,
    surfaceContainerHighest = surfaceRaised,
    surfaceBright = surfaceRaised,
    surfaceDim = background,
    inverseSurface = text,
    inverseOnSurface = onPrimary,
    outline = outline,
    outlineVariant = divider,
    error = seat.avoid,
    onError = onPrimary,
    scrim = background,
)

/**
 * The app theme. Dark only (spec §7): there is deliberately no light variant and
 * no dynamic colour.
 */
@Composable
fun CinexTheme(content: @Composable () -> Unit) {
    val colors = DarkCinexColors
    CompositionLocalProvider(
        LocalCinexColors provides colors,
        LocalCinexTextStyles provides DefaultCinexTextStyles,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            typography = CinexTypography,
            content = content,
        )
    }
}

/** Accessors for the app-specific tokens that Material 3 has no slot for. */
object CinexTheme {
    val colors: CinexColors
        @Composable @ReadOnlyComposable get() = LocalCinexColors.current

    val textStyles: CinexTextStyles
        @Composable @ReadOnlyComposable get() = LocalCinexTextStyles.current
}
