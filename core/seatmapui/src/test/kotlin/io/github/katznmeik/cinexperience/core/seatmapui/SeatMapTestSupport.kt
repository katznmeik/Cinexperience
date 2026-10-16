package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.SeatId

/* Lookups only the tests use. */

/** The number text of every seat, from the derived labels ([io.github.katznmeik.cinexperience.core.model.labels]). */
internal fun LayoutLabels.seatNumbers(): Map<SeatId, String> =
    buildMap { seats.forEach { (id, info) -> put(id, info.label.seatNumberText) } }

/** The centre of [seatId]'s cell, `null` if the layout has no such seat. */
internal fun SeatMapGeometry.seatCenter(seatId: SeatId): Offset? =
    layout.positionOf(seatId)?.let { cellCenter(it.row, it.column) }

/**
 * The first seat in a row labelled [rowLabel] whose seat label is [seat] (a
 * number like "10", or a fixed label like "R"). Both are trimmed and compared
 * ignoring case; every kind of place is numbered, so the kind doesn't matter.
 * A [rowLabel] that is `null`, blank or "-" ([LayoutRow.NO_NAME]) finds the seat
 * among the rows without a name.
 */
internal fun List<SeatRowChoice>.findSeat(rowLabel: String?, seat: String): SeatId? {
    val row = rowLabel?.trim()?.takeUnless { it.isEmpty() || it == LayoutRow.NO_NAME }
    return asSequence()
        .filter { if (row == null) it.isUnnamed else it.label.equals(row, ignoreCase = true) }
        .flatMap { it.seats }
        .firstOrNull { it.label.seat.equals(seat.trim(), ignoreCase = true) }
        ?.seatId
}
