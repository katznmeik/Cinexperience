package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A ticket hides its booking number in texts and keeps its own seat list. Test data is invented. */
class TicketPrivacyTest {

    private val number = BookingNumber("TEST123")
    private val qr = QrModules.of(21) { row, column -> row == column }

    @Test
    fun toStringHidesTheBookingNumber() {
        assertEquals("BookingNumber(hidden)", number.toString())
        assertEquals("TEST123", number.value)
        assertFalse("$number".contains("TEST123"))
        val text = Ticket(4, number, listOf(TicketSeat("7", "10")), qr).toString()
        assertFalse(text.contains("TEST123"), text)
        assertTrue(text.contains("visitId=4"), text)
        assertTrue(text.contains("QrModules(21 × 21)"), text)
    }

    @Test
    fun aTicketKeepsItsOwnCopyOfTheSeats() {
        val seats = mutableListOf(TicketSeat("7", "10"))
        val ticket = Ticket(4, number, seats)
        seats += TicketSeat("7", "11")
        seats[0] = TicketSeat("8", "1")
        assertEquals(listOf(TicketSeat("7", "10")), ticket.seats)
    }

    @Test
    fun ticketsBehaveLikeDataClasses() {
        val ticket = Ticket(4, number, listOf(TicketSeat("7", "10")), qr)
        val same = Ticket(4, BookingNumber("TEST123"), listOf(TicketSeat("7", "10")), QrModules.of(21) { r, c -> r == c })
        assertEquals(ticket, same)
        assertEquals(ticket.hashCode(), same.hashCode())
        assertEquals(Ticket(9, number, ticket.seats, qr), ticket.copy(visitId = 9))
        assertEquals(Ticket(4, number, ticket.seats), ticket.copy(qrModules = null))
        assertNotEquals(ticket, ticket.copy(bookingNumber = BookingNumber("TEST456")))
        assertNotEquals(ticket, ticket.copy(seats = listOf(TicketSeat("7", "11"))))
        // copy checks the rules again.
        assertFailsWith<IllegalArgumentException> { ticket.copy(seats = emptyList()) }
    }
}
