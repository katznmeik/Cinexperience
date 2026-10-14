package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatState
import io.github.katznmeik.cinexperience.core.ui.theme.SeatColors

/**
 * Colour of a seat on the map (spec §5); the tokens are `CinexTheme.colors.seat`.
 *
 * @property label legend and screen-reader name, e.g. "Great".
 */
enum class SeatTone(val label: String) {
    NO_DATA("No data"),
    GREAT("Great"),
    OK("OK"),

    /** Drawn with a × mark as the colour-blind cue (spec §7). */
    AVOID("Avoid"),
}

/**
 * How one seat is drawn on the [SeatMap]. Seats without an entry are drawn
 * [Neutral] ("no data" grey). The seat's kind symbol (wheelchair, companion,
 * sofa) comes from the layout and is combined with any visual.
 */
@Immutable
data class SeatVisual(
    val tone: SeatTone = SeatTone.NO_DATA,
    /** Marked with a ⭐ (spec §5). */
    val isFavourite: Boolean = false,
) {
    companion object {
        val Neutral: SeatVisual = SeatVisual()
    }
}

/** "great", "OK, favourite", "no data": the seat's state for screen readers. */
val SeatVisual.stateText: String
    get() {
        val toneText = if (tone == SeatTone.OK) tone.label else tone.label.lowercase()
        return if (isFavourite) "$toneText, favourite" else toneText
    }

/** "Row 7, Seat 10, great, favourite": a seat's label with its state. */
fun seatDescription(label: SeatLabel, visual: SeatVisual): String = "${label.description}, ${visual.stateText}"

fun SeatColour?.toSeatTone(): SeatTone = when (this) {
    SeatColour.GREEN -> SeatTone.GREAT
    SeatColour.YELLOW -> SeatTone.OK
    SeatColour.RED -> SeatTone.AVOID
    null -> SeatTone.NO_DATA
}

/** The visual of a seat from its computed state (colour and favourite, spec §5). */
fun SeatState.toSeatVisual(): SeatVisual = SeatVisual(colour.toSeatTone(), isFavourite)

internal fun SeatColors.colorOf(tone: SeatTone): Color = when (tone) {
    SeatTone.NO_DATA -> noData
    SeatTone.GREAT -> great
    SeatTone.OK -> ok
    SeatTone.AVOID -> avoid
}

/** Colour of the number and the marks drawn on a seat of [tone] (at least 4.5:1 against its fill). */
internal fun SeatColors.onColorOf(tone: SeatTone): Color = when (tone) {
    SeatTone.NO_DATA -> onNoData
    SeatTone.GREAT -> onGreat
    SeatTone.OK -> onOk
    SeatTone.AVOID -> onAvoid
}
