package io.github.katznmeik.cinexperience.core.data.db

import io.github.katznmeik.cinexperience.core.model.SeatLayout

/**
 * The id from which a hall's new seats must be numbered: the stored mark, raised
 * to cover the stored layout and every seat id that the hall's visits or
 * overrides reference (defensive; they should always be below the mark).
 * Call inside a transaction.
 */
internal suspend fun CinexDatabase.seatIdMark(hall: HallEntity, storedLayout: SeatLayout?): Int =
    maxOf(
        hall.nextSeatId,
        storedLayout?.nextSeatId ?: SeatLayout.FIRST_SEAT_ID,
        (visitDao().maxSeatId(hall.id) ?: 0) + 1,
        (seatOverrideDao().maxSeatId(hall.id) ?: 0) + 1,
    )
