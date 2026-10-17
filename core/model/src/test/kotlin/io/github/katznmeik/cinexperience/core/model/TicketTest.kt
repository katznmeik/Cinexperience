package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ticket's value types (spec §4.7): every rule is checked when a value is created. Test data is invented. */
class TicketTest {

    private val seat = TicketSeat("7", "10")

    @Test
    fun bookingNumbersHave4To16CapitalLettersAndDigits() {
        for (valid in listOf("TEST123", "ABCD", "0000", "A1B2C3D4E5F6G7H8")) {
            assertTrue(BookingNumber.isValid(valid), valid)
            assertEquals(valid, BookingNumber(valid).value)
            assertEquals(BookingNumber(valid), BookingNumber.ofOrNull(valid))
        }
        val invalid = listOf(
            "", "ABC", "A".repeat(17), "test123", "TEST 123", " TEST123", "TEST123 ", "TEST-123", "T\u00C9ST123", "TEST\u0661\u0662",
            "TEST\u200B123", "TEST\n123", "\uFF34EST123", // fullwidth T
        )
        for (text in invalid) {
            assertFalse(BookingNumber.isValid(text), text)
            assertNull(BookingNumber.ofOrNull(text), text)
            assertFailsWith<IllegalArgumentException>(text) { BookingNumber(text) }
        }
    }

    @Test
    fun aTicketSeatsRowFollowsTheRowLabelRules() {
        for (row in listOf("7", "C", "R1", "Loge", "R".repeat(TextLimits.MAX_ROW_LABEL_LENGTH))) {
            assertEquals(row, TicketSeat(row, "10").row)
        }
        assertNull(TicketSeat(null, "12").row) // a row without a name
        val invalid = listOf("", " ", " 7", "7 ", "7\u200B", "\u202E7", "7\n", "R".repeat(TextLimits.MAX_ROW_LABEL_LENGTH + 1), LayoutRow.NO_NAME)
        for (row in invalid) {
            assertFalse(TicketSeat.isValidRow(row), row)
            assertFailsWith<IllegalArgumentException>(row) { TicketSeat(row, "10") }
        }
    }

    @Test
    fun aTicketSeatsSeatIsASeatNumberOrAFixedLabel() {
        // Numbers of any length an Int holds; up to 4 other characters, like a fixed label ("07" is one too).
        for (text in listOf("1", "10", "0", "12345", Int.MAX_VALUE.toString(), "R", "W1", "ABCD", "07", "-1")) {
            assertTrue(TicketSeat.isValidSeat(text), text)
            assertEquals(text, TicketSeat("1", text).seat)
        }
        val invalid = listOf("", " ", "ABCDE", "R ", " R", "-12345", "012345", "2147483648", "123456789012", "R\u200B", "\u0000")
        for (text in invalid) {
            assertFalse(TicketSeat.isValidSeat(text), text)
            assertFailsWith<IllegalArgumentException>(text) { TicketSeat("1", text) }
        }
    }

    @Test
    fun aTicketSeatReadsLikeTheAppsSeatLabels() {
        assertEquals("Row 7 · Seat 10", TicketSeat("7", "10").label.shortText)
        assertEquals("Row 8 · Seat R", TicketSeat("8", "R").label.shortText)
        assertEquals("Seat 12", TicketSeat(null, "12").label.shortText)
    }

    @Test
    fun aTicketHas1To20DifferentSeats() {
        val number = BookingNumber("TEST123")
        val twenty = List(Ticket.MAX_SEATS) { TicketSeat("5", "${it + 1}") }
        assertEquals(20, Ticket(0, number, twenty).seats.size)
        assertNull(Ticket(1, number, listOf(seat)).qrModules)

        assertFailsWith<IllegalArgumentException> { Ticket(1, number, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Ticket(1, number, twenty + TicketSeat("5", "21")) }
        assertFailsWith<IllegalArgumentException> { Ticket(1, number, listOf(seat, TicketSeat("8", "1"), seat)) }
        // The same seat number in two rows is two seats.
        assertEquals(2, Ticket(1, number, listOf(TicketSeat("7", "10"), TicketSeat("8", "10"))).seats.size)
    }

    @Test
    fun ticketsCompareByContent() {
        val qr = QrModules.of(21) { row, column -> (row + column) % 2 == 0 }
        val same = QrModules.of(21) { row, column -> (row + column) % 2 == 0 }
        assertEquals(Ticket(3, BookingNumber("TEST123"), listOf(seat), qr), Ticket(3, BookingNumber("TEST123"), listOf(seat), same))
    }
}
