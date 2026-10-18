package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDocument
import io.github.katznmeik.cinexperience.core.ticket.testing.CMapStyle
import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.InventedTicketPdf
import io.github.katznmeik.cinexperience.core.ticket.testing.PageSpec
import io.github.katznmeik.cinexperience.core.ticket.testing.TicketSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalTime

/** The reader on invented tickets with the structure of Yorck's (spec §11.2). */
class TicketPdfReaderTest {

    private val decoder = ImageIoLuminanceDecoder()
    private val reader = TicketPdfReader(decoder)

    private fun read(pdf: ByteArray): TicketPdfContent {
        val result = reader.read(pdf)
        assertTrue("Expected a ticket, got $result", result is TicketPdfResult.Read)
        return (result as TicketPdfResult.Read).content
    }

    @Test(timeout = 10_000)
    fun theInventedTicketHasYorcksStructure() {
        val document = PdfDocument.open(InventedTicketPdf.build())
        val pages = document.pages()

        assertEquals(1, pages.size)
        val content = document.decodedData(document.contentStreams(pages.single()).single())
        assertTrue(String(content, Charsets.ISO_8859_1).startsWith("/GSa gs /CSp cs /CSp CS\n1 0 0 -1 0 1191 cm"))
        assertEquals(listOf("FlateDecode"), document.filters(document.contentStreams(pages.single()).single()))
        assertTrue(pages.single().resources?.containsKey("Font") == true)
    }

    @Test(timeout = 10_000)
    fun readsEveryFieldOfTheInventedTicket() {
        val content = read(InventedTicketPdf.build())

        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertEquals(TicketPdfField.Found("The Quiet Projectionist"), content.filmTitle)
        assertEquals(TicketPdfField.Found("delphi LUX"), content.cinema)
        assertEquals(TicketPdfField.Found(LocalDate.of(2026, 10, 24)), content.date)
        assertEquals(TicketPdfField.Found(LocalTime.of(20, 15)), content.startTime)
        assertEquals(TicketPdfField.Found("4"), content.hall)
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat("7", "10"))), content.seats)
        val grid = content.qrCode.valueOrNull
        assertEquals(21, grid?.size)
        assertTrue(grid!!.toBooleanArray().contentEquals(InventedTicketPdf.QR_GRID))
    }

    @Test(timeout = 10_000)
    fun onlyTheSmallImageIsDecodedNeverTheLogo() {
        read(InventedTicketPdf.build())

        assertEquals(listOf(116 to 116), decoder.sizes)
    }

    @Test(timeout = 10_000)
    fun neverReturnsTheBookerNorAnyOtherPrintedText() {
        val content = read(InventedTicketPdf.build())
        val values = listOf(content.bookingNumber, content.filmTitle, content.cinema, content.date, content.startTime, content.hall, content.seats)
            .map { it.valueOrNull.toString() }

        listOf("Erika", "Mustermann", "Gebucht", "1234567", "98765432101", "9,50", "Normal", "Teststra", "12345", "2026-10-17", "example.invalid", "Kat.")
            .forEach { text -> assertFalse("'$text' must not be read", values.any { it.contains(text) }) }
    }

    @Test(timeout = 10_000)
    fun readsEveryCMapForm() {
        for (style in CMapStyle.entries) {
            val content = read(InventedTicketPdf.build(cmapStyle = style))
            assertEquals(style.name, TicketPdfField.Found("The Quiet Projectionist"), content.filmTitle)
            assertEquals(style.name, TicketPdfField.Found("TEST123"), content.bookingNumber)
        }
    }

    @Test(timeout = 10_000)
    fun readsAnUncompressedContentStream() {
        assertEquals(TicketPdfField.Found("delphi LUX"), read(InventedTicketPdf.build(compress = false)).cinema)
    }

    @Test(timeout = 10_000)
    fun valuesMayFollowOnTheNextLineOrAfterAColon() {
        val below = read(InventedTicketPdf.build(listOf(PageSpec(tickets = listOf(TicketSpec(valuesBelow = true)), bookingNumberBeside = true))))
        val colons = read(InventedTicketPdf.build(listOf(PageSpec(tickets = listOf(TicketSpec(colons = true))))))

        for (content in listOf(below, colons)) {
            assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
            assertEquals(TicketPdfField.Found("delphi LUX"), content.cinema)
            assertEquals(TicketPdfField.Found(LocalDate.of(2026, 10, 24)), content.date)
            assertEquals(TicketPdfField.Found(LocalTime.of(20, 15)), content.startTime)
            assertEquals(TicketPdfField.Found("4"), content.hall)
            assertEquals(TicketPdfField.Found(listOf(PrintedSeat("7", "10"))), content.seats)
        }
    }

    @Test(timeout = 10_000)
    fun aTitleOverTwoLinesIsJoined() {
        val content = read(InventedTicketPdf.build(listOf(PageSpec(tickets = listOf(TicketSpec(title = listOf("The Quiet", "Projectionist")))))))

        assertEquals(TicketPdfField.Found("The Quiet Projectionist"), content.filmTitle)
        assertEquals(TicketPdfField.Found("4"), content.hall)
    }

    @Test(timeout = 10_000)
    fun aTitleOfMoreThanThreeLinesIsLeftOpen() {
        val three = read(InventedTicketPdf.build(listOf(PageSpec(tickets = listOf(TicketSpec(title = listOf("The", "Quiet", "Projectionist"))))))).filmTitle
        val four = read(InventedTicketPdf.build(listOf(PageSpec(tickets = listOf(TicketSpec(title = listOf("The", "Very", "Quiet", "Projectionist"))))))).filmTitle

        assertEquals(TicketPdfField.Found("The Quiet Projectionist"), three)
        assertEquals(TicketPdfField.NotFound(NotFoundReason.INVALID), four)
    }

    @Test(timeout = 10_000)
    fun aTitleThatIsNotBoldIsNotTaken() {
        val content = read(InventedTicketPdf.build(listOf(PageSpec(tickets = listOf(TicketSpec(titleBold = false))))))

        assertEquals(TicketPdfField.NotFound(NotFoundReason.MISSING), content.filmTitle)
        assertEquals(TicketPdfField.Found("delphi LUX"), content.cinema)
    }

    @Test(timeout = 10_000)
    fun withoutTheBookerLineTheFirstLabelsStillWin() {
        // The conditions text below has lines starting with "Platz", "Zeit", "Datum", "Saal", "Kino", "Reihe".
        val content = read(InventedTicketPdf.build(listOf(PageSpec(tickets = listOf(TicketSpec(bookedBy = null))))))

        assertEquals(TicketPdfField.Found("delphi LUX"), content.cinema)
        assertEquals(TicketPdfField.Found(LocalTime.of(20, 15)), content.startTime)
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat("7", "10"))), content.seats)
    }

    @Test(timeout = 10_000)
    fun readsFromAStream() {
        val result = reader.read(ByteArrayInputStream(InventedTicketPdf.build()))

        assertEquals(TicketPdfField.Found("TEST123"), (result as TicketPdfResult.Read).content.bookingNumber)
    }

    @Test(timeout = 10_000)
    fun aPdfWithoutTicketFieldsIsNotATicket() {
        val page = PageSpec(tickets = emptyList(), bookingNumber = null)

        assertEquals(TicketPdfResult.Refused(TicketPdfError.NOT_A_TICKET), reader.read(InventedTicketPdf.build(listOf(page))))
    }

    @Test(timeout = 10_000)
    fun aStrayTicketHeadingWithoutLabelsIsNotATicket() {
        val page = PageSpec(tickets = listOf(TicketSpec(title = emptyList(), cinema = null, date = null, time = null, hall = null, row = null, seat = null)), bookingNumber = null)

        assertEquals(TicketPdfResult.Refused(TicketPdfError.NOT_A_TICKET), reader.read(InventedTicketPdf.build(listOf(page))))
    }
}
