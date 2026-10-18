package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.testing.ExtraText
import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.InventedTicketPdf
import io.github.katznmeik.cinexperience.core.ticket.testing.PageSpec
import io.github.katznmeik.cinexperience.core.ticket.testing.TestFont
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
        // "Saal" without a value; the next line in its column is the category.
        for ((label, value) in listOf("Kat." to "1 x Normal", "Kat.1 x Normal" to "", "KAT" to "Normal", "Kategorie" to "Parkett")) {
            val content = read(page(TicketSpec(hall = "", categoryLabel = label, category = value, categoryTop = 279.0)))

            assertEquals(label, invalid, content.hall)
            assertNeverShown(content, "Kat", "KAT", "Normal", "Parkett", "Erika", "Mustermann")
        }
    }

    @Test(timeout = 10_000)
    fun aValueThatStartsLikeACategoryIsNone() {
        for (hall in listOf("Kat.1 x Normal", "Kategorie Parkett", "Kat 2")) {
            assertEquals(hall, invalid, read(page(TicketSpec(hall = hall))).hall)
        }
        assertEquals(invalid, read(page(TicketSpec(row = "Kat"))).seats)
        // Words that only begin with the same letters are fine.
        assertEquals(TicketPdfField.Found("Katakombe"), read(page(TicketSpec(hall = "Katakombe"))).hall)
    }

    @Test(timeout = 10_000)
    fun aValueThatMentionsTheBookerIsNone() {
        // In a block, such a line is a booker line and closes the block.
        val content = read(page(TicketSpec(hall = "gebucht von Erika Mustermann")))
        assertEquals(TicketPdfField.NotFound(NotFoundReason.MISSING), content.hall)
        assertNeverShown(content, "Erika", "Mustermann", "gebucht")

        // A booking number that would pass its rule, beside or below its label.
        for (beside in listOf(true, false)) {
            val booking = read(PageSpec(bookingNumber = "GEBUCHT", bookingNumberBeside = beside))
            assertEquals("beside $beside", invalid, booking.bookingNumber)
            assertNeverShown(booking, "GEBUCHT")
        }
    }

    @Test(timeout = 10_000)
    fun aBookerLineInAnyCaseOrSpellingClosesTheBlock() {
        // A regular booker line right below "Saal" without a value: only its label tells it apart.
        val labels = listOf("gebucht von", "GEBUCHT VON", "Gebucht${TestFont.ZERO_WIDTH_SPACE}von", "Gebucht  von:", "gebucht:")
        for (label in labels) {
            val content = read(page(TicketSpec(hall = "", category = null, bookedByIndent = 0.0, bookedByTop = 290.0, bookedByLabel = label, bookedByBold = false)))

            assertEquals(label, invalid, content.hall)
            assertNeverShown(content, "Erika", "Mustermann", "ebucht", "EBUCHT")
        }
    }

    @Test(timeout = 10_000)
    fun aNameOnItsOwnLineAboveTheBookerLabelIsNeverAValue() {
        // Bold, as on Yorck's ticket, the first bold line ends the labels; regular, it stands right above a booker line.
        for (bold in listOf(true, false)) {
            val content = read(page(TicketSpec(hall = "", category = null, bookedByIndent = 0.0, bookedByTop = 290.0, bookedByNameFirst = true, bookedByBold = bold)))

            assertEquals("bold $bold", invalid, content.hall)
            assertNeverShown(content, "Erika", "Mustermann", "Gebucht")
        }
    }

    @Test(timeout = 10_000)
    fun aBookerLabelLeftOfTheBlockStillClosesIt() {
        // The label starts in the left column, the (regular) name in the block's column.
        val content = read(page(TicketSpec(hall = "", category = null, bookedByIndent = 0.0, bookedByLabelIndent = -190.0, bookedByTop = 290.0, bookedByBold = false)))

        assertEquals(invalid, content.hall)
        assertNeverShown(content, "Erika", "Mustermann", "Gebucht")
    }

    @Test(timeout = 10_000)
    fun aBookerLineWhereTheTitleWouldBeIsNoTitle() {
        val content = read(page(TicketSpec(title = emptyList(), bookedByLabel = "gebucht von", bookedByIndent = 0.0, bookedByTop = 117.0)))

        assertEquals(TicketPdfField.NotFound(NotFoundReason.MISSING), content.filmTitle)
        assertNeverShown(content, "Erika", "Mustermann", "gebucht")
    }

    @Test(timeout = 10_000)
    fun aThreeLineTitleKeepsTheBookingNumber() {
        // The block's own "Gebucht von" line then comes within three font sizes below the booking number.
        val content = read(page(TicketSpec(title = listOf("The Long Night", "of the Quiet", "Projectionist"))))

        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertEquals(TicketPdfField.Found("The Long Night of the Quiet Projectionist"), content.filmTitle)
        assertEquals(TicketPdfField.Found("4"), content.hall)
    }

    @Test(timeout = 10_000)
    fun theBookingNumberIsNeverANameBesideOrBelowABookerLabel() {
        // An empty "Buchungsnummer" in the left column, the name below it, and "Gebucht von"
        // left of the block on the name's line or on the label's line.
        for (bookerTop in listOf(398.0, 379.0)) {
            val texts = listOf(ExtraText(56.0, 398.0, "MUELLER", bold = true, size = 14.0), ExtraText(200.0, bookerTop, "Gebucht von:", bold = true, size = 15.0))
            val content = read(PageSpec(bookingNumber = "", extraTexts = texts))

            assertEquals("booker at $bookerTop", invalid, content.bookingNumber)
            assertNeverShown(content, "MUELLER")
        }
    }

    @Test(timeout = 10_000)
    fun aNameAboveAFarBookerLabelIsNeverAValue() {
        // The (regular) name right below "Saal" without a value, "Gebucht von" more than three font sizes below it.
        val content = read(
            page(TicketSpec(hall = "", category = null, bookedByIndent = 0.0, bookedByTop = 290.0, bookedByNameFirst = true, bookedByBold = false, bookedByLabelGap = 70.0)),
        )

        assertEquals(invalid, content.hall)
        assertNeverShown(content, "Erika", "Mustermann")
    }

    @Test(timeout = 10_000)
    fun aNoteMentioningBookingsLeftOfTheBlockClosesNothing() {
        // Beside the title, and on a line of its own between the labels.
        for (top in listOf(117.0, 230.0)) {
            val content = read(PageSpec(extraTexts = listOf(ExtraText(56.0, top, "Bereits gebuchte Tickets", size = 10.0))))

            assertEquals("note at $top", TicketPdfField.Found("The Quiet Projectionist"), content.filmTitle)
            assertEquals("note at $top", TicketPdfField.Found("delphi LUX"), content.cinema)
            assertEquals("note at $top", TicketPdfField.Found("4"), content.hall)
            assertEquals("note at $top", TicketPdfField.Found(listOf(PrintedSeat("7", "10"))), content.seats)
        }
    }

    @Test(timeout = 10_000)
    fun aTitleMayMentionBookingsOrStartLikeACategory() {
        for (title in listOf("Ausgebucht", "Kategorie C")) {
            val content = read(page(TicketSpec(title = listOf(title))))

            assertEquals(TicketPdfField.Found(title), content.filmTitle)
            assertEquals(title, TicketPdfField.Found("4"), content.hall)
        }
    }

    @Test(timeout = 10_000)
    fun theBookingNumberIsNeverTakenFromATicketBlock() {
        // An empty "Buchungsnummer" in the block, right above the booker's name on its own line.
        val content = read(page(TicketSpec(labelAboveBooker = "Buchungsnummer", bookedBy = "MUSTERMANN", bookedByNameFirst = true, bookedByIndent = 0.0)))

        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertNeverShown(content, "MUSTERMANN")
    }

    @Test(timeout = 10_000)
    fun theBookingNumberIsNeverTheNameAboveABookerLine() {
        // In the left column: an empty "Buchungsnummer", the name in bold below it, then "Gebucht von".
        val ticket = TicketSpec(labelAboveBooker = "Buchungsnummer", bookedBy = "MUSTERMANN", bookedByNameFirst = true, bookedByIndent = -219.0, bookedByTop = 398.0)
        val content = read(PageSpec(tickets = listOf(ticket), bookingNumber = null))

        assertEquals(invalid, content.bookingNumber)
        assertEquals(TicketPdfField.Found("4"), content.hall)
        assertNeverShown(content, "MUSTERMANN")
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
