package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.InventedTicketPdf
import io.github.katznmeik.cinexperience.core.ticket.testing.PageSpec
import io.github.katznmeik.cinexperience.core.ticket.testing.TicketSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Values come only from their own ticket block, never from the booker's line or the
 * category; titles may contain label words; seats of several blocks are joined only
 * for one showing; nothing of the ticket shows in a result's text.
 */
class TicketFieldsScopeTest {

    private val decoder = ImageIoLuminanceDecoder()
    private val reader = TicketPdfReader(decoder)
    private val invalid = TicketPdfField.NotFound(NotFoundReason.INVALID)
    private val ambiguous = TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)

    private fun read(vararg pages: PageSpec): TicketPdfContent = read(InventedTicketPdf.build(pages.toList()))

    private fun read(pdf: ByteArray): TicketPdfContent {
        val result = reader.read(pdf)
        assertTrue("Expected a ticket, got $result", result is TicketPdfResult.Read)
        return (result as TicketPdfResult.Read).content
    }

    private fun page(ticket: TicketSpec) = PageSpec(tickets = listOf(ticket))

    /** Every value read, as text. */
    private fun values(content: TicketPdfContent): List<String> =
        listOf(content.bookingNumber, content.filmTitle, content.cinema, content.date, content.startTime, content.hall, content.seats)
            .mapNotNull { it.valueOrNull?.let { value -> if (value is List<*>) value.joinToString { seat -> (seat as PrintedSeat).let { s -> "${s.row} ${s.seat}" } } else value.toString() } }

    private fun assertNeverShown(content: TicketPdfContent, vararg texts: String) {
        val read = values(content) + content.toString() + TicketPdfResult.Read(content).toString()
        for (text in texts) assertFalse("'$text' was read", read.any { it.contains(text) })
    }

    @Test(timeout = 10_000)
    fun aLabelWithoutValueDoesntTakeTheCategoryBelow() {
        // "Saal" without a value; the next line in its column is "Kat. 1 x Normal".
        val content = read(page(TicketSpec(hall = "")))

        assertEquals(invalid, content.hall)
        assertNeverShown(content, "Kat.", "Normal", "Erika", "Mustermann")
    }

    @Test(timeout = 10_000)
    fun aLabelWithoutValueNeverTakesTheBookersLine() {
        // No category; the booker's line right below "Saal", in its column.
        val content = read(page(TicketSpec(hall = "", category = null, bookedByIndent = 0.0, bookedByTop = 290.0)))

        assertEquals(invalid, content.hall)
        assertNeverShown(content, "Gebucht", "Erika", "Mustermann")
    }

    @Test(timeout = 10_000)
    fun theBookersLineClosesTheBlockAlsoWithAColon() {
        // No seat labels in the block; below "Gebucht von: …", the conditions have a line starting with "Platz".
        val content = read(page(TicketSpec(row = null, seat = null, bookedByLabel = "Gebucht von:")))

        assertEquals(TicketPdfField.NotFound(NotFoundReason.MISSING), content.seats)
        assertNeverShown(content, "Erika", "Mustermann")
    }

    @Test(timeout = 10_000)
    fun aTitleMayStartWithALabelWord() {
        val content = read(page(TicketSpec(title = listOf("Zeit der Unschuld"))))

        assertEquals(TicketPdfField.Found("Zeit der Unschuld"), content.filmTitle)
        assertEquals(TicketPdfField.Found(LocalTime.of(20, 15)), content.startTime)
    }

    @Test(timeout = 10_000)
    fun aTitleMayWrapOntoALabelWord() {
        val time = read(page(TicketSpec(title = listOf("Die bleierne", "Zeit"))))
        val cinema = read(page(TicketSpec(title = listOf("Das grosse", "Kino"))))

        assertEquals(TicketPdfField.Found("Die bleierne Zeit"), time.filmTitle)
        assertEquals(TicketPdfField.Found(LocalTime.of(20, 15)), time.startTime)
        assertEquals(TicketPdfField.Found("Das grosse Kino"), cinema.filmTitle)
        assertEquals(TicketPdfField.Found("delphi LUX"), cinema.cinema)
    }

    @Test(timeout = 10_000)
    fun seatsOfBlocksThatDontNameTheSameShowingInFullAreLeftOpen() {
        // The second ticket has no hall: its seat may belong to another showing.
        val content = read(page(TicketSpec(seat = "10")), page(TicketSpec(seat = "11", hall = null)))

        assertEquals(TicketPdfField.Found("4"), content.hall)
        assertEquals(ambiguous, content.seats)
    }

    @Test(timeout = 10_000)
    fun aResultsTextShowsNoValue() {
        val content = read(page(TicketSpec()))
        val text = TicketPdfResult.Read(content).toString()

        assertTrue(text, text.contains("Found(hidden)"))
        for (value in listOf("TEST123", "Quiet", "delphi", "2026", "20:15", "PrintedSeat", "10")) {
            assertFalse("'$value' in $text", text.contains(value))
        }
        assertEquals("QrModuleGrid(size=21)", content.qrCode.valueOrNull.toString())
    }

    @Test(timeout = 10_000)
    fun aQrImageOfMoreThan256KilobytesIsNeverDecoded() {
        val padded = InventedTicketPdf.QR_JPEG + ByteArray(TicketPdfLimits.MAX_QR_IMAGE_BYTES)
        val content = read(InventedTicketPdf.build(qrJpeg = padded))

        assertEquals(TicketPdfField.NotFound(NotFoundReason.MISSING), content.qrCode)
        assertEquals(emptyList<Pair<Int, Int>>(), decoder.sizes)
    }
}
