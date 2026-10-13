package io.github.katznmeik.cinexperience.core.model

/** A layout from row patterns with ids from 1, as for a hall that never had seats. */
internal fun layoutOf(vararg patterns: String): SeatLayout = SeatLayout.fromPatterns(*patterns, firstSeatId = 1)
