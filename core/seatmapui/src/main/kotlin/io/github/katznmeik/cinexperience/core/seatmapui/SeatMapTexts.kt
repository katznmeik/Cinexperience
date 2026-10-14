package io.github.katznmeik.cinexperience.core.seatmapui

import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.ViewingAngle
import io.github.katznmeik.cinexperience.core.ui.text.countText
import kotlin.math.roundToInt

/**
 * Screen-reader description of a seat map, e.g. "Seat map of Saal 1: 10 rows,
 * 183 seats, including 2 wheelchair spaces, 2 companion seats, 8 sofa places.
 * Screen at the top."
 */
fun seatMapDescription(hallName: String, labels: LayoutLabels): String {
    // Rows with seats, also those without a name (they have no row label).
    val rows = labels.seats.values.distinctBy { it.position.row }.size
    val kinds = labels.seats.values.groupingBy { it.label.kind }.eachCount()
    val special = listOfNotNull(
        kinds[SeatKind.WHEELCHAIR]?.let { countText(it, "wheelchair space") },
        kinds[SeatKind.COMPANION]?.let { countText(it, "companion seat") },
        kinds[SeatKind.SOFA]?.let { countText(it, "sofa place") },
    )
    val including = if (special.isEmpty()) "" else ", including ${special.joinToString(", ")}"
    return "Seat map of $hallName: ${countText(rows, "row")}, ${countText(labels.seats.size, "seat")}$including. " +
        "Screen at the top."
}

/**
 * Screen-reader summary of the seat colours, e.g. "Seat colours: 12 great,
 * 8 OK, 3 avoid, 160 no data. Favourite: Row 7, Seat 8." ("Favourite: Seat 12,
 * sofa." in a row without a name). Tones without seats are left out; seats
 * missing from [visuals] count as no data.
 */
fun seatStatesDescription(visuals: Map<SeatId, SeatVisual>, labels: LayoutLabels): String {
    val counts = labels.seats.keys.groupingBy { (visuals[it] ?: SeatVisual.Neutral).tone }.eachCount()
    val tones = listOf(SeatTone.GREAT, SeatTone.OK, SeatTone.AVOID, SeatTone.NO_DATA)
        .mapNotNull { tone -> counts[tone]?.let { "$it ${SeatVisual(tone).stateText}" } }
    val favourite = labels.seats.keys.firstOrNull { visuals[it]?.isFavourite == true }?.let { labels.labelOf(it) }
    return buildString {
        if (tones.isNotEmpty()) append("Seat colours: ${tones.joinToString(", ")}.")
        if (favourite != null) append(" Favourite: ${favourite.description}.")
    }.trim()
}

/** "≈ 38° viewing angle" from the horizontal field of view. */
fun viewingAngleText(angle: ViewingAngle): String = "≈ ${angle.fieldOfViewDegrees.roundToInt()}° viewing angle"
