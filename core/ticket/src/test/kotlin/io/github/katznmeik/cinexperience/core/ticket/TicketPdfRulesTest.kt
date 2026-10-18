package io.github.katznmeik.cinexperience.core.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The field checks (spec §11.2 rule 2, like §11.1 rule 7). */
class TicketPdfRulesTest {

    @Test
    fun bookingNumbersAreUpperCaseLettersAndDigitsAsTheAppStoresThem() {
        // The one rule: core:model's BookingNumber (4 to 16 characters).
        listOf("TEST123", "AB12", "ABCDEFGHIJ012345", "1234567", "ABCDEFG").forEach { assertTrue(it, TicketPdfRules.isValidBookingNumber(it)) }
        listOf("", "AB1", "ABCDEFGHIJ0123456", "test123", "TEST 123", "TEST-123", "TEST123\u200b", "T\u00c4ST123", "\uff34EST123")
            .forEach { assertFalse(it, TicketPdfRules.isValidBookingNumber(it)) }
    }

    @Test
    fun titlesAreCleanedTextUpToTheLimit() {
        assertEquals("The Quiet Projectionist", TicketPdfRules.title("The Quiet Projectionist"))
        assertEquals("Am\u00e9lie: \"Die fabelhafte Welt\" (OmU) & mehr!", TicketPdfRules.title("Am\u00e9lie: \"Die fabelhafte Welt\" (OmU) & mehr!"))
        assertEquals("<b>", TicketPdfRules.title("<b>"))
        assertEquals("x".repeat(200), TicketPdfRules.title("x".repeat(200)))
        assertNull(TicketPdfRules.title("x".repeat(201)))
        assertNull(TicketPdfRules.title(""))
        assertNull(TicketPdfRules.title("Bidi\u202eTrick"))
        assertNull(TicketPdfRules.title(" padded"))
    }

    @Test
    fun cinemaAndHallNamesAreLettersDigitsAndALittlePunctuation() {
        listOf("Odeon", "delphi LUX", "Kino International", "Filmtheater am Friedrichshain", "Babylon Kreuzberg", "Yorck", "2", "Luxbox", "Saal 2", "Caf\u00e9 & Bar", "St. Ann's")
            .forEach { assertEquals(it, it, TicketPdfRules.cinema(it)) }
        listOf("", "-", " Yorck", "Yorck-", "Kino <script>", "Kino\u0000", "a\u202eb", "Yorck;", "x".repeat(81))
            .forEach { assertNull(it, TicketPdfRules.cinema(it)) }
        assertEquals("x".repeat(40), TicketPdfRules.hall("x".repeat(40)))
        assertNull(TicketPdfRules.hall("x".repeat(41)))
    }

    @Test
    fun datesAreRealDaysInDdMmYyyy() {
        assertEquals(LocalDate.of(2026, 10, 24), TicketPdfRules.date("24.10.2026"))
        assertEquals(LocalDate.of(2028, 2, 29), TicketPdfRules.date("29.02.2028"))
        listOf("29.02.2027", "31.04.2026", "00.10.2026", "24.13.2026", "24.10.26", "2026-10-24", "24.10.1999", "24.10.2100", "24.10.2026 ", "Sa., 24.10.2026", "\u0662\u0664.10.2026")
            .forEach { assertNull(it, TicketPdfRules.date(it)) }
    }

    @Test
    fun timesAreHhMmWithAnOptionalUhr() {
        assertEquals(LocalTime.of(20, 15), TicketPdfRules.time("20:15 Uhr"))
        assertEquals(LocalTime.of(0, 5), TicketPdfRules.time("00:05"))
        assertEquals(LocalTime.of(23, 59), TicketPdfRules.time("23:59 Uhr"))
        listOf("24:00", "20:60", "8:15", "20.15", "20:15 h", "20:15Uhr", "20:15 Uhr!", "").forEach { assertNull(it, TicketPdfRules.time(it)) }
    }

    @Test
    fun oneSeatOrAListOrARangeInOneRow() {
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat("7", "10"))), TicketPdfRules.seats("7", "10"))
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat("R1", "R"))), TicketPdfRules.seats("R1", "R"))
        // A row printed as "-" has no name: null, as core:model's TicketSeat stores it.
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat(null, "12"))), TicketPdfRules.seats("-", "12"))
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat("7", "0"), PrintedSeat("7", "1A"))), TicketPdfRules.seats("7", "0, 1A"))
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat("C", "10"), PrintedSeat("C", "11"))), TicketPdfRules.seats("C", "10, 11"))
        assertEquals(TicketPdfField.Found(listOf(PrintedSeat("7", "10"), PrintedSeat("7", "11"))), TicketPdfRules.seats("7", "10,11,10"))
        assertEquals(TicketPdfField.Found((10..12).map { PrintedSeat("7", "$it") }), TicketPdfRules.seats("7", "10\u201312"))
        assertEquals(TicketPdfField.Found((10..11).map { PrintedSeat("7", "$it") }), TicketPdfRules.seats("7", "10 - 11"))
    }

    @Test
    fun seatsThatCantBePairedUpAreLeftForManualEntry() {
        val invalid = TicketPdfField.NotFound(NotFoundReason.INVALID)
        val ambiguous = TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)

        assertEquals(TicketPdfField.NotFound(NotFoundReason.MISSING), TicketPdfRules.seats(null, null))
        assertEquals(invalid, TicketPdfRules.seats("7", null))
        assertEquals(invalid, TicketPdfRules.seats(null, "10"))
        assertEquals(ambiguous, TicketPdfRules.seats("7, 8", "10, 11"))
        assertEquals(invalid, TicketPdfRules.seats("7", "10 11"))
        assertEquals(invalid, TicketPdfRules.seats("7", "10 und 11"))
        assertEquals(invalid, TicketPdfRules.seats("7", "11-10"))
        assertEquals(invalid, TicketPdfRules.seats("7", "10-10"))
        assertEquals(invalid, TicketPdfRules.seats("7", "1-40"))
        assertEquals(invalid, TicketPdfRules.seats("7", "12345"))
        assertEquals(invalid, TicketPdfRules.seats("Reihe 7", "10"))
        assertEquals(invalid, TicketPdfRules.seats("7", ""))
        assertEquals(invalid, TicketPdfRules.seats("7", "10,"))
        assertEquals(invalid, TicketPdfRules.seats("7", (1..21).joinToString(",")))
        assertEquals(TicketPdfField.Found((1..20).map { PrintedSeat("7", "$it") }), TicketPdfRules.seats("7", (1..20).joinToString(", ")))
    }

    @Test
    fun seatsAreAsciiNumbersWithoutLeadingZerosOrShortFixedLabels() {
        val invalid = TicketPdfField.NotFound(NotFoundReason.INVALID)

        for (seat in listOf("08", "010", "\u0662", "\uff11", "\u00c4", "R-1", "R 1", "ABCDE", "08-10", "0-2")) {
            assertEquals(seat, invalid, TicketPdfRules.seats("7", seat))
        }
        for (row in listOf("\u0662", "R 1", "R-1", "ABCDEFGHI", "--")) {
            assertEquals(row, invalid, TicketPdfRules.seats(row, "10"))
        }
    }

    @Test
    fun cleaningRemovesControlAndFormatCharactersAndJoinsWhitespace() {
        assertEquals("The Quiet Projectionist", TicketText.clean(" \tThe\u00a0Quiet\u2028\u200bProjectionist\u0000\n"))
        assertEquals("ab", TicketText.clean("a\u202e\u2066b\ufeff"))
        assertEquals("\ud83c\udfac", TicketText.clean("\ud83c\udfac\ud800"))
        assertEquals(1, TicketText.length("\ud83c\udfac"))
    }
}
