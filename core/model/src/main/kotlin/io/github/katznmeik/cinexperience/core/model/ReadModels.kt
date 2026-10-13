package io.github.katznmeik.cinexperience.core.model

/*
 * Read models that the repositories in core:data emit for the screens.
 * All scores are computed with the functions in Scores.kt / SeatColours.kt.
 */

/** One entry of the cinema list (spec §6.1). */
data class CinemaSummary(
    val cinema: Cinema,
    val overallScore: Double?,
    val visitCount: Int,
    val hallCount: Int,
)

/** One hall in the cinema detail (spec §6.2). */
data class HallSummary(
    val hall: Hall,
    val overallScore: Double?,
    val visitCount: Int,
    /** Label of the favourite seat, `null` if none is set (or it has no label). */
    val favouriteSeat: SeatLabel?,
)

/** Cinema detail (spec §6.2): header scores and the halls, sorted by name. */
data class CinemaDetail(
    val cinema: Cinema,
    val scores: CinemaScores,
    val halls: List<HallSummary>,
)

/**
 * Hall detail (spec §6.3).
 *
 * @property labels derived labels of [Hall.layout], `null` without layout.
 * @property seats state of every seat in the layout; empty without layout.
 * @property visits the hall's visits, newest first.
 */
data class HallDetail(
    val hall: Hall,
    val cinema: Cinema,
    val scores: HallScores,
    val visits: List<Visit>,
    val labels: LayoutLabels?,
    val seats: Map<SeatId, SeatState>,
    val overrides: List<SeatOverride>,
) {
    val favouriteSeat: SeatId? get() = seats.values.firstOrNull { it.isFavourite }?.seatId
}

/** One entry of the "All visits" list (spec §6.8). */
data class VisitListItem(
    val visit: Visit,
    val cinemaId: Long,
    val cinemaName: String,
    val hallName: String,
    /** Label of the visit's seat in the current layout; `null` if no seat or the seat was removed. */
    val seatLabel: SeatLabel?,
)
