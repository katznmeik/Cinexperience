package io.github.katznmeik.cinexperience.feature.visits.edit

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.feature.visits.AddVisitRoute
import io.github.katznmeik.cinexperience.feature.visits.EditVisitRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The imported ticket travels to Add visit in the route's optional arguments (spec §6.10); unreadable values are left out. */
class VisitImportRouteTest {

    private val draft = TicketDraft(BookingNumber("TEST123"), listOf(TicketSeat("7", "10"), TicketSeat(null, "12"), TicketSeat("8", "R")), SampleTickets.qrModules())
    private val prefill = VisitPrefillArgs(
        cinemaId = 5,
        date = LocalDate.of(2026, 10, 17),
        filmTitle = "The Quiet Projectionist",
        format = ScreeningFormat.OMU,
        startTime = LocalTime.of(20, 15),
        seatId = SeatId(97),
        ticket = draft,
        notes = listOf("Not on the ticket: film title", "Hall on the ticket: \u2068Saal 9\u2069 (not in the app)"),
    )

    @Test
    fun theImportArrivesWhole() {
        val route = AddVisitRoute.of(504L, prefill)
        assertEquals(504L, route.hallId)
        assertEquals("20:15", route.startTime)
        assertEquals(listOf("7", "-", "8"), route.ticketRows)
        assertEquals(prefill, route.prefill())
    }

    @Test
    fun aPrefillWithoutTicketStaysAsBefore() {
        val showing = VisitPrefillArgs(cinemaId = 7, date = LocalDate.of(2026, 10, 17), filmTitle = "Paper Kites", format = ScreeningFormat.GERMAN_DUB)
        val route = AddVisitRoute.of(null, showing)
        assertNull(route.bookingNumber)
        assertNull(route.ticketRows)
        assertNull(route.ticketNotes)
        assertEquals(showing, route.prefill())
    }

    @Test
    fun unreadableTicketArgumentsAreLeftOut() {
        val valid = AddVisitRoute.of(504L, prefill)
        assertNull("no valid booking number, no ticket", valid.copy(bookingNumber = "test 123").prefill()?.ticket)
        assertEquals("rows and seats don't pair up", emptyList<TicketSeat>(), valid.copy(ticketRows = listOf("7")).prefill()?.ticket?.seats)
        assertEquals(
            "an invalid seat is left out",
            listOf(TicketSeat("7", "10"), TicketSeat("8", "R")),
            valid.copy(ticketSeats = listOf("10", " 12", "R")).prefill()?.ticket?.seats,
        )
        assertNull("a QR code that can't be decoded", valid.copy(qrModules = "21:not base64").prefill()?.ticket?.qrModules)
        assertNull(valid.copy(startTime = "20:15:30").prefill()?.startTime)
        assertNull(valid.copy(startTime = "late").prefill()?.startTime)
        assertNull(valid.copy(seatId = 0).prefill()?.seatId)
        val manyNotes = valid.copy(ticketNotes = List(50) { "x".repeat(1_000) }).prefill()!!.notes
        assertEquals(VisitPrefillArgs.MAX_NOTES, manyNotes.size)
        assertEquals(VisitPrefillArgs.MAX_NOTE_LENGTH, manyNotes.first().length)
    }

    @Test
    fun aDateOutsideTheDatePickersYearsIsIgnored() {
        val valid = AddVisitRoute.of(504L, prefill)
        assertNull(valid.copy(date = "2101-01-01").prefill()?.date)
        assertNull(valid.copy(date = "1899-12-31").prefill()?.date)
        assertNull(valid.copy(date = "+999999999-12-31").prefill()?.date)
        assertEquals(LocalDate.of(2100, 12, 31), valid.copy(date = "2100-12-31").prefill()?.date)
        assertEquals(LocalDate.of(1900, 1, 1), valid.copy(date = "1900-01-01").prefill()?.date)
    }

    @Test
    fun theVisitRouteSaysImportedBeforeOnlyWhenAsked() {
        assertFalse(EditVisitRoute(5).importedBefore)
        assertFalse(EditVisitRoute(5, importedBefore = true).qrCodeNotStored)
    }
}
