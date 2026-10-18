package io.github.katznmeik.cinexperience.feature.tickets

import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The ticket screen's texts (spec §6.11). */
class TicketTextsTest {

    private fun ticket(date: LocalDate, today: LocalDate, start: LocalTime? = LocalTime.of(20, 15)) = TicketDetails(
        filmTitle = SampleTickets.FILM,
        date = date,
        startTime = start,
        today = today,
        cinemaName = "delphi LUX",
        hallName = "Saal 4",
        seats = listOf(TicketSeatLine(SeatLabel("7", "10"), isOwn = true)),
        bookingNumber = SampleTickets.BOOKING_NUMBER,
        qrModules = null,
        missingQrCode = MissingQrCode.NOT_STORED,
    )

    @Test
    fun theDayOfTheShowing() {
        val today = LocalDate.of(2026, 10, 15)
        assertEquals("Today · Thursday 15 Oct · 20:15", ticket(today, today).showingText())
        assertEquals("Saturday 17 Oct · 20:15", ticket(today.plusDays(2), today).showingText())
        assertEquals("Saturday 17 Oct", ticket(today.plusDays(2), today, start = null).showingText())
    }

    @Test
    fun aShowingInAnotherYearNamesIt() {
        val newYearsEve = LocalDate.of(2026, 12, 31)
        assertEquals("Tomorrow · Friday 1 Jan 2027 · 20:15", ticket(LocalDate.of(2027, 1, 1), newYearsEve).showingText())
        // A ticket from last year, opened in the week after the showing.
        assertEquals("Wednesday 30 Dec 2026 · 20:15", ticket(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 3)).showingText())
    }

    @Test
    fun thePlace() {
        val today = LocalDate.of(2026, 10, 15)
        assertEquals("delphi LUX · Saal 4", ticket(today, today).placeText())
        assertEquals(null, ticket(today, today).copy(hallName = null).placeText())
    }
}
