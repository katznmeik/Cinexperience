package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HallTest {
    @Test
    fun layoutIsDrawnWhenReadableOrStoredButUnreadable() {
        val hall = Hall(cinemaId = 1, name = "Saal 1")
        assertFalse(hall.isLayoutDrawn)
        assertTrue(hall.copy(layout = SeatLayout.fromPatterns("SS", firstSeatId = 1)).isLayoutDrawn)
        assertTrue(hall.copy(layoutUnreadable = true).isLayoutDrawn)
    }
}
