package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The imported ticket before Save (spec §6.10) and matching printed seats with a
 * hall's labels (spec §11.2, Matching). Test data is invented.
 */
class TicketDraftTest {

    private val number = BookingNumber("TEST123")
    private val qr = QrModules.of(21) { row, column -> row == column }

    @Test
    fun theDraftBecomesATicketOfANewVisit() {
        val seats = listOf(TicketSeat("7", "10"), TicketSeat("7", "11"))
        val draft = TicketDraft(number, seats, qr)
        assertEquals(Ticket(0, number, seats, qr), draft.toTicket())
        assertEquals(Ticket(0, number, seats), draft.toTicket(qrModules = null), "an expired QR code is left out")
    }

    @Test
    fun withoutPrintedSeatsTheTicketHasNone() {
        assertEquals(Ticket(0, number, emptyList(), qr), TicketDraft(number, emptyList(), qr).toTicket())
    }

    @Test
    fun aDraftFollowsTheTicketsSeatRules() {
        assertFailsWith<IllegalArgumentException> { TicketDraft(number, listOf(TicketSeat("7", "10"), TicketSeat("7", "10"))) }
        assertFailsWith<IllegalArgumentException> { TicketDraft(number, List(Ticket.MAX_SEATS + 1) { TicketSeat("7", "${it + 1}") }) }
        TicketDraft(number, List(Ticket.MAX_SEATS) { TicketSeat("7", "${it + 1}") })
    }

    @Test
    fun itsTextHidesTheBookingNumberAndTheQrCode() {
        val text = TicketDraft(number, listOf(TicketSeat("7", "10")), qr).toString()
        assertFalse(text.contains("TEST123"), text)
        assertFalse(text.contains(qr.encode()), text)
    }

    @Test
    fun printedSeatsMatchTheLabelsOfTheLayout() {
        val labels = layoutOf("SSSS", "SSSS").labels()
        assertEquals(SeatId(3), labels.seatOf(TicketSeat("1", "3")))
        assertEquals(SeatId(6), labels.seatOf(TicketSeat("2", "2")))
        assertNull(labels.seatOf(TicketSeat("2", "9")), "no such seat")
        assertNull(labels.seatOf(TicketSeat("7", "1")), "no such row")
        assertNull(labels.seatOf(TicketSeat(null, "1")), "the row is part of the label")
    }

    @Test
    fun rowsWithoutANameMatchOnlyTicketSeatsWithoutARow() {
        // Kant Kino Saal 3's shape: every sofa its own unnamed row, numbered across the room.
        val layout = layoutOf("..FF", "FF..")
            .updateRow(0) { it.copy(label = LayoutRow.NO_NAME, blockStartNumbers = listOf(3)) }
            .updateRow(1) { it.copy(label = LayoutRow.NO_NAME) }
        val labels = layout.labels()
        assertEquals(SeatId(2), labels.seatOf(TicketSeat(null, "4")))
        assertEquals(SeatId(3), labels.seatOf(TicketSeat(null, "1")))
        assertNull(labels.seatOf(TicketSeat("1", "4")))
    }

    @Test
    fun fixedLabelsCompareExactly() {
        val layout = layoutOf("SSSW").withFixedLabel(0, 3, "R")
        val labels = layout.labels()
        assertEquals(SeatId(4), labels.seatOf(TicketSeat("1", "R")))
        assertNull(labels.seatOf(TicketSeat("1", "r")), "case counts")
        assertNull(labels.seatOf(TicketSeat("1", "4")), "the fixed label uses up no number")
    }

    @Test
    fun aLabelThatTwoSeatsShareMatchesNothing() {
        // Numbers restart in every block: "Row 1, Seat 1" twice.
        val layout = layoutOf("SS|SS").updateRow(0) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }
        val labels = layout.labels()
        assertNull(labels.seatOf(TicketSeat("1", "1")))
        assertNull(labels.seatOf(TicketSeat("1", "2")))
    }
}
