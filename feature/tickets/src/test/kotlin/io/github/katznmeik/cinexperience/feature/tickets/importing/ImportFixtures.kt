package io.github.katznmeik.cinexperience.feature.tickets.importing

import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.ticket.NotFoundReason
import io.github.katznmeik.cinexperience.core.ticket.PrintedSeat
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfContent
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalTime

/**
 * INVENTED ticket contents for the import tests, never a real ticket: booking number
 * "TEST123", "The Quiet Projectionist" at delphi LUX, Saal 4, Row 7 · Seat 10 and 11,
 * Saturday 17 Oct 2026 at 20:15 (two days after the tests' clock, SampleProgram.FETCHED_AT).
 */
internal object ImportFixtures {
    const val BOOKING_NUMBER = "TEST123"
    val DATE: LocalDate = LocalDate.of(2026, 10, 17)
    val START: LocalTime = SampleTickets.START

    val MISSING: TicketPdfField.NotFound = TicketPdfField.NotFound(NotFoundReason.MISSING)
    val INVALID: TicketPdfField.NotFound = TicketPdfField.NotFound(NotFoundReason.INVALID)

    fun <T> found(value: T): TicketPdfField<T> = TicketPdfField.Found(value)

    /** A read ticket; pass `null` to leave a field off the ticket ([MISSING]). */
    fun content(
        bookingNumber: String? = BOOKING_NUMBER,
        filmTitle: String? = SampleTickets.FILM,
        cinema: String? = "delphi LUX",
        date: LocalDate? = DATE,
        startTime: LocalTime? = START,
        hall: String? = "4",
        seats: List<PrintedSeat>? = listOf(PrintedSeat("7", "10"), PrintedSeat("7", "11")),
    ): TicketPdfContent = TicketPdfContent(
        bookingNumber = bookingNumber.field(),
        filmTitle = filmTitle.field(),
        cinema = cinema.field(),
        date = date.field(),
        startTime = startTime.field(),
        hall = hall.field(),
        seats = seats.field(),
        // The import takes the QR code as the model's QrModules (TicketReadResult.Read).
        qrCode = MISSING,
    )

    private fun <T> T?.field(): TicketPdfField<T> = if (this == null) MISSING else TicketPdfField.Found(this)

    /** The cinemas as Add visit and the import read them. */
    fun cinemas(store: FakeStore = SampleTickets.store()): List<CinemaDetail> =
        runBlocking { FakeCinexRepository(store).observeCinemaDetails().first() }
}
