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
    /** Decorative lines between rows (no meaning of their own, so no contrast minimum). */
    val divider: Color,
    /** Borders of controls that also carry a text label (chips, pill buttons): decorative. */
    val outline: Color,
    /**
     * Borders of controls identified by their outline alone (e.g. small number fields):
     * at least 3:1 against [surface], [surfaceRaised], [canvas] and [background].
     */
    val fieldOutline: Color,
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
    /** No ratings yet: a dark fill, so rated seats stand out. Drawn with a [noDataOutline] rim. */
    val noData: Color,
    /**
     * Rim of "no data" seats: lifts the seat shape to at least 3:1 against the card
     * (`surface`) and the background (WCAG non-text contrast) while it still reads as empty.
     */
    val noDataOutline: Color,
)

internal object CinexPalette {
    val Background = Color(0xFF0E0D0C)
    val Surface = Color(0xFF1A1918)
    val SurfaceRaised = Color(0xFF232220)
    val Canvas = Color(0xFF161514)
    val Divider = Color(0xFF2B2A28)
    val Outline = Color(0xFF3A3835)
    val OutlineStrong = Color(0xFF75716B)
    val Text = Color(0xFFF3F0EA)
    val TextMuted = Color(0xFFA39F97)
    val OnPrimary = Color(0xFF0E0D0C)
    val NavigationBar = Color(0xFF141312)

    val SeatGreat = Color(0xFF3DBE7A)
    val SeatOk = Color(0xFFF4D35E)
    val SeatAvoid = Color(0xFFD9454B)
    val SeatNoData = Color(0xFF3A3835)
    val SeatNoDataOutline = OutlineStrong
}

val DarkCinexColors = CinexColors(
    background = CinexPalette.Background,
    surface = CinexPalette.Surface,
    surfaceRaised = CinexPalette.SurfaceRaised,
    canvas = CinexPalette.Canvas,
    divider = CinexPalette.Divider,
    outline = CinexPalette.Outline,
    fieldOutline = CinexPalette.OutlineStrong,
    text = CinexPalette.Text,
    textMuted = CinexPalette.TextMuted,
    onPrimary = CinexPalette.OnPrimary,
    navigationBar = CinexPalette.NavigationBar,
    seat = SeatColors(
        great = CinexPalette.SeatGreat,
        ok = CinexPalette.SeatOk,
        avoid = CinexPalette.SeatAvoid,
        noData = CinexPalette.SeatNoData,
        noDataOutline = CinexPalette.SeatNoDataOutline,
    ),
)
