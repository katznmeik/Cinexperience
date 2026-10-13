package io.github.katznmeik.cinexperience.feature.seatmap.map

import io.github.katznmeik.cinexperience.core.model.LayoutLabels
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
    val rows = labels.rowLabels.count { it != null }
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

/** "≈ 38° viewing angle" from the horizontal field of view. */
fun viewingAngleText(angle: ViewingAngle): String = "≈ ${angle.fieldOfViewDegrees.roundToInt()}° viewing angle"
