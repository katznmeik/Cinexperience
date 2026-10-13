package io.github.katznmeik.cinexperience.core.model

/** A seat that an edit would remove, with its label in the *old* layout. */
data class RemovedSeat(val seatId: SeatId, val label: SeatLabel)

/**
 * Seats that carry data: every seat referenced by a visit (rated or not) and every
 * seat with a non-empty override.
 */
fun seatsWithData(visits: List<Visit>, overrides: List<SeatOverride>): Set<SeatId> =
    visits.mapNotNullTo(HashSet()) { it.seatId } + overrides.filterNot { it.isEmpty }.map { it.seatId }

/**
 * The §6.5 warning: seats of [old] that have data ([seatsWithData]) and are no
 * longer in [new], in the old layout's row-major order. Empty when nothing with
 * data would disappear. A `null` [new] layout removes every seat; a `null` [old]
 * layout has none to remove. Renumbering, relabelling and inserting rows or
 * columns never remove a seat, because seats are matched by [SeatId].
 */
fun removedSeatsWithData(old: SeatLayout?, new: SeatLayout?, seatsWithData: Set<SeatId>): List<RemovedSeat> {
    if (old == null) return emptyList()
    val remaining = new?.seatIds.orEmpty()
    val labels = old.labels()
    return labels.seats.values
        .filter { it.id in seatsWithData && it.id !in remaining }
        .map { RemovedSeat(it.id, it.label) }
}
