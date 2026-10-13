package io.github.katznmeik.cinexperience.core.ui.icon

/**
 * Path data (24×24 viewport, meant for a round 2 px stroke) of the symbols drawn
 * inside non-standard seats: wheelchair space, companion seat, sofa place. The
 * seat map draws them as vector paths so they stay sharp at every zoom level;
 * [CinexIcons] builds the matching icons from the same data.
 */
object SeatKindGlyphs {
    /** Person in a wheelchair: head, torso and leg, and the open wheel. */
    val Wheelchair: List<String> = listOf(
        "M11 4.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
        "M12 7.5v6h5l2 5",
        "M12 10.5h4",
        "M9.5 10.5A5.5 5.5 0 1 0 16.5 17.5",
    )

    /** Two people side by side. */
    val CompanionSeat: List<String> = listOf(
        "M5.5 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "M2.5 20v-1a5 5 0 0 1 5-5h2a5 5 0 0 1 5 5v1",
        "M13.5 8a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0",
        "M17 14h.5a4 4 0 0 1 4 4v2",
    )

    /** A two-seat sofa: backrest, arms and seat, legs. */
    val Sofa: List<String> = listOf(
        "M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3",
        "M2 13a2 2 0 0 1 4 0v2h12v-2a2 2 0 0 1 4 0v5H2z",
        "M5 18v2M19 18v2",
    )
}
