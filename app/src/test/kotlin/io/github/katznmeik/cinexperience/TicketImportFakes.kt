package io.github.katznmeik.cinexperience

import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.ticket.NotFoundReason
import io.github.katznmeik.cinexperience.core.ticket.PrintedSeat
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfContent
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfField
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketFileSource
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketReadResult
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.Collections

/**
 * Fakes of the ticket import for the app tests, with INVENTED data only: every file
 * "is" the ticket "TEST123" for "The Quiet Projectionist" at delphi LUX, Saal 4, Row 7 ·
 * Seat 10 and 11 (plus a seat in a row without a name and a fixed label, which Saal 4
 * doesn't have), Saturday 17 Oct 2026 at 20:15, with [SampleTickets.qrModules] (25 × 25,
 * to tell it from the stored one). No PDF is read.
 */
internal class FakeTicketFiles : TicketFileSource {
    /** The addresses opened, in order (from the import's background thread). */
    val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun open(uri: String): InputStream {
        opened += uri
        return ByteArrayInputStream("%PDF-1.4 invented".toByteArray())
    }
}

internal object FakeTicketReader : TicketReader {
    val QR = SampleTickets.qrModules(25)

    val content: TicketPdfContent = TicketPdfContent(
        bookingNumber = TicketPdfField.Found("TEST123"),
        filmTitle = TicketPdfField.Found(SampleTickets.FILM),
        cinema = TicketPdfField.Found("delphi LUX"),
        date = TicketPdfField.Found(LocalDate.of(2026, 10, 17)),
        startTime = TicketPdfField.Found(SampleTickets.START),
        hall = TicketPdfField.Found("4"),
        seats = TicketPdfField.Found(listOf(PrintedSeat("7", "10"), PrintedSeat("7", "11"), PrintedSeat(null, "12"), PrintedSeat("8", "R"))),
        qrCode = TicketPdfField.NotFound(NotFoundReason.MISSING),
    )

    override fun read(bytes: ByteArray): TicketReadResult = TicketReadResult.Read(content, QR)
}
