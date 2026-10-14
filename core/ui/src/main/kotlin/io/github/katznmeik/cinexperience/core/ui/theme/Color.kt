package io.github.katznmeik.cinexperience.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The monochrome palette (spec §7). Black and white only — the seat colours are
 * the only hues in the app, so the seat map stands out.
 *
 * Always use these tokens (via [CinexTheme.colors] or `MaterialTheme.colorScheme`)
 * instead of hard-coded colours in feature modules.
 */
@Immutable
data class CinexColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    /** Slightly darker than [surface]: drawing areas such as the layout editor canvas. */
    val canvas: Color,
    val divider: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
    val onPrimary: Color,
    val navigationBar: Color,
    val seat: SeatColors,
)

/** Seat map colours (spec §5 "Seat colour"); drawn by the seat map in core:seatmapui. */
@Immutable
data class SeatColors(
    /** Average seat rating ≥ 4.0, or manual override GREEN. */
    val great: Color,
    /** Average seat rating ≥ 2.5 and < 4.0, or manual override YELLOW. */
    val ok: Color,
    /** Average seat rating < 2.5, or manual override RED. Drawn with a × mark. */
    val avoid: Color,
    /** No ratings yet. */
    val noData: Color,
)

internal object CinexPalette {
    val Background = Color(0xFF0E0D0C)
    val Surface = Color(0xFF1A1918)
    val SurfaceRaised = Color(0xFF232220)
    val Canvas = Color(0xFF161514)
    val Divider = Color(0xFF2B2A28)
    val Outline = Color(0xFF3A3835)
    val Text = Color(0xFFF3F0EA)
    val TextMuted = Color(0xFFA39F97)
    val OnPrimary = Color(0xFF0E0D0C)
    val NavigationBar = Color(0xFF141312)

    val SeatGreat = Color(0xFF3DBE7A)
    val SeatOk = Color(0xFFF4D35E)
    val SeatAvoid = Color(0xFFD9454B)
    val SeatNoData = Color(0xFF3A3835)
}

val DarkCinexColors = CinexColors(
    background = CinexPalette.Background,
    surface = CinexPalette.Surface,
    surfaceRaised = CinexPalette.SurfaceRaised,
    canvas = CinexPalette.Canvas,
    divider = CinexPalette.Divider,
    outline = CinexPalette.Outline,
    text = CinexPalette.Text,
    textMuted = CinexPalette.TextMuted,
    onPrimary = CinexPalette.OnPrimary,
    navigationBar = CinexPalette.NavigationBar,
    seat = SeatColors(
        great = CinexPalette.SeatGreat,
        ok = CinexPalette.SeatOk,
        avoid = CinexPalette.SeatAvoid,
        noData = CinexPalette.SeatNoData,
    ),
)
