package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.InventedTicketPdf
import io.github.katznmeik.cinexperience.core.ticket.testing.PageSpec
import io.github.katznmeik.cinexperience.core.ticket.testing.TestQrCodes
import io.github.katznmeik.cinexperience.core.ticket.testing.TicketSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Field by field: several seats (no real sample yet, spec §11.2 "Still open"),
 * missing and invalid values, the QR code's states.
 */
class TicketFieldsTest {

    private val reader = TicketPdfReader(ImageIoLuminanceDecoder())
    private val missing = TicketPdfField.NotFound(NotFoundReason.MISSING)
    private val invalid = TicketPdfField.NotFound(NotFoundReason.INVALID)
    private val ambiguous = TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)

    private fun read(vararg pages: PageSpec): TicketPdfContent = read(InventedTicketPdf.build(pages.toList()))

    private fun read(pdf: ByteArray): TicketPdfContent {
        val result = reader.read(pdf)
        assertTrue("Expected a ticket, got $result", result is TicketPdfResult.Read)
        return (result as TicketPdfResult.Read).content
    }

    private fun page(ticket: TicketSpec) = PageSpec(tickets = listOf(ticket))

    private fun seats(vararg seats: Pair<String, String>) = TicketPdfField.Found(seats.map { PrintedSeat(it.first, it.second) })

    @Test
    fun severalSeatsInOneRowOnOneTicket() {
        assertEquals(seats("7" to "10", "7" to "11"), read(page(TicketSpec(seat = "10, 11"))).seats)
        assertEquals(seats("7" to "10", "7" to "11", "7" to "12"), read(page(TicketSpec(seat = "10\u201312"))).seats)
    }

    @Test
    fun oneTicketPerPageJoinsTheSeats() {
        val content = read(page(TicketSpec(seat = "10")), page(TicketSpec(seat = "11")), page(TicketSpec(row = "6", seat = "1")))

        assertEquals(seats("7" to "10", "7" to "11", "6" to "1"), content.seats)
        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertEquals(TicketPdfField.Found("4"), content.hall)
        assertEquals(21, content.qrCode.valueOrNull?.size)
    }

    @Test
    fun twoTicketsOneBelowTheOtherOnOnePage() {
        val content = read(PageSpec(tickets = listOf(TicketSpec(seat = "10"), TicketSpec(seat = "11"))))

        assertEquals(seats("7" to "10", "7" to "11"), content.seats)
        assertEquals(TicketPdfField.Found("The Quiet Projectionist"), content.filmTitle)
    }

    @Test
    fun theSameSeatTwiceCountsOnce() {
        assertEquals(seats("7" to "10"), read(page(TicketSpec()), page(TicketSpec())).seats)
    }

    @Test
    fun ticketsSideBySideCantBeToldApart() {
        val content = read(PageSpec(tickets = listOf(TicketSpec(seat = "10"), TicketSpec(seat = "11")), sideBySide = true))

        assertEquals(ambiguous, content.seats)
        assertEquals(ambiguous, content.hall)
        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
    }

    @Test
    fun moreTicketsOnOnePageThanFitAreAmbiguous() {
        val content = read(PageSpec(tickets = List(5) { TicketSpec(seat = "${it + 1}") }))

        assertEquals(ambiguous, content.seats)
        assertEquals(ambiguous, content.cinema)
        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
    }

    @Test
    fun ticketsOfDifferentShowingsLeaveTheFieldsOpen() {
        val content = read(page(TicketSpec(hall = "4")), page(TicketSpec(hall = "5", seat = "11")))

        assertEquals(ambiguous, content.hall)
        assertEquals(ambiguous, content.seats)
        assertEquals(TicketPdfField.Found("delphi LUX"), content.cinema)
        assertEquals(TicketPdfField.Found(LocalDate.of(2026, 10, 24)), content.date)
    }

    @Test
    fun differentBookingNumbersLeaveItOpen() {
        val content = read(PageSpec(bookingNumber = "TEST123"), PageSpec(bookingNumber = "TEST456"))

        assertEquals(ambiguous, content.bookingNumber)
        assertEquals(seats("7" to "10"), content.seats)
    }

    @Test
    fun seatsOfSeveralRowsOnOneTicketCantBePairedUp() {
        assertEquals(ambiguous, read(page(TicketSpec(row = "7, 8", seat = "10, 11"))).seats)
    }

    @Test
    fun aTicketWithoutItsSeatMakesTheListIncomplete() {
        val content = read(page(TicketSpec(seat = "10")), page(TicketSpec(row = null, seat = null)))

        assertEquals(invalid, content.seats)
    }

    @Test
    fun aPageWithoutATicketBlockDoesntMatter() {
        val content = read(PageSpec(), PageSpec(tickets = emptyList(), bookingNumber = null, qrImage = false))

        assertEquals(seats("7" to "10"), content.seats)
        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
    }

    @Test
    fun eachMissingLabelIsMissingAndTheRestStillFound() {
        val content = read(page(TicketSpec(title = emptyList(), cinema = null, date = null, time = null, hall = null)))

        assertEquals(missing, content.filmTitle)
        assertEquals(missing, content.cinema)
        assertEquals(missing, content.date)
        assertEquals(missing, content.startTime)
        assertEquals(missing, content.hall)
        assertEquals(seats("7" to "10"), content.seats)
        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertEquals(missing, read(PageSpec(bookingNumber = null, conditions = false)).bookingNumber)
        // The conditions text has a line starting with "Buchungsnummer", in the ticket block's area: never looked at.
        assertEquals(missing, read(PageSpec(bookingNumber = null)).bookingNumber)
        assertEquals(missing, read(page(TicketSpec(row = null, seat = null))).seats)
    }

    @Test
    fun anInvalidBookingNumberIsNeverUsed() {
        for (number in listOf("test-123", "TEST 123", "AB1", "T\u00c4ST123", "TEST1234567890ABC")) {
            assertEquals(number, invalid, read(PageSpec(bookingNumber = number)).bookingNumber)
        }
    }

    @Test
    fun invalidValuesAreNeverUsed() {
        val content = read(
            page(TicketSpec(cinema = "delphi LUX;", date = "31.02.2026", time = "25:00 Uhr", hall = "<b>", seat = "10 11", title = listOf("x".repeat(201)))),
        )

        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertEquals(invalid, content.filmTitle)
        assertEquals(invalid, content.cinema)
        assertEquals(invalid, content.date)
        assertEquals(invalid, content.startTime)
        assertEquals(invalid, content.hall)
        assertEquals(invalid, content.seats)
    }

    @Test
    fun onlyTheBookingNumberIsStillATicket() {
        val content = read(PageSpec(tickets = emptyList()))

        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertEquals(missing, content.filmTitle)
        assertEquals(missing, content.seats)
    }

    @Test
    fun onlyInvalidValuesAreNotATicket() {
        val page = PageSpec(tickets = listOf(TicketSpec(title = emptyList(), cinema = "-", date = "x", time = "x", hall = "-", seat = "x y")), bookingNumber = "x")

        assertEquals(TicketPdfResult.Refused(TicketPdfError.NOT_A_TICKET), reader.read(InventedTicketPdf.build(listOf(page))))
    }

    @Test
    fun theQrCodeIsMissingInvalidOrFound() {
        assertEquals(missing, read(PageSpec(qrImage = false)).qrCode)
        assertEquals(invalid, read(InventedTicketPdf.build(qrJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))).qrCode)
        assertEquals(invalid, read(InventedTicketPdf.build(qrJpeg = "not a jpeg".toByteArray())).qrCode)
        // A JPEG of something else: decoded, no grid.
        val noise = TestQrCodes.render(BooleanArray(21 * 21) { it % 3 == 0 }, 21)
        assertEquals(invalid, read(InventedTicketPdf.build(qrJpeg = TestQrCodes.jpeg(noise))).qrCode)
        // The size in the PDF doesn't match the JPEG.
        assertEquals(invalid, read(InventedTicketPdf.build(qrPixels = 120)).qrCode)
    }

    @Test
    fun imagesOverTheSizeLimitAreNeverDecoded() {
        val decoder = ImageIoLuminanceDecoder()
        val content = (TicketPdfReader(decoder).read(InventedTicketPdf.build(qrPixels = 513)) as TicketPdfResult.Read).content

        assertEquals(missing, content.qrCode)
        assertEquals(emptyList<Pair<Int, Int>>(), decoder.sizes)
    }
}
