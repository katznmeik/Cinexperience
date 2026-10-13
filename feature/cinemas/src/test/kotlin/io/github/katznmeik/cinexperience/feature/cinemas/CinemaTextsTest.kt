package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class CinemaTextsTest {
    private fun summary(hall: Hall, visits: Int) = HallSummary(hall, null, visits, null)

    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1")

    @Test
    fun hallMetaCoversLayoutStates() {
        assertEquals("Layout not drawn yet", hallMeta(summary(hall, 0)))
        assertEquals("Layout not drawn yet · 2 visits", hallMeta(summary(hall, 2)))
        val drawn = hall.copy(layout = SeatLayout.fromPatterns("SSS", firstSeatId = 1))
        assertEquals("3 seats · no visits", hallMeta(summary(drawn, 0)))
        assertEquals("3 seats · 1 visit", hallMeta(summary(drawn, 1)))
        val unreadable = hall.copy(layoutUnreadable = true)
        assertEquals("Layout can't be shown", hallMeta(summary(unreadable, 0)))
        assertEquals("Layout can't be shown · 1 visit", hallMeta(summary(unreadable, 1)))
    }

    @Test
    fun cinemaMetaUsesStreetPartOfAddress() {
        assertEquals("Kantstraße 10 · 7 halls", cinemaMeta(Cinema(name = "x", address = "Kantstraße 10, 10623 Berlin"), 7))
        assertEquals("1 hall", cinemaMeta(Cinema(name = "x"), 1))
    }
}
