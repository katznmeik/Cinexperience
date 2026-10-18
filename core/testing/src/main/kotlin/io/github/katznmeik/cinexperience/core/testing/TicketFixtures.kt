package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.Visit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/**
 * INVENTED tickets (spec §4.7) for tests and screenshots, never a real one: booking
 * number "TEST123", "The Quiet Projectionist" at delphi LUX, Saal 4, Row 7 · Seat 10
 * and Row 7 · Seat 11. The QR modules ([qrModules]) are drawn, not read from a
 * ticket: the three finder patterns, the timing patterns and noise from a fixed
 * seed, so they look like a QR code but hold nothing. Never used by the app.
 */
object SampleTickets {
    val BOOKING_NUMBER: BookingNumber = BookingNumber("TEST123")
    const val FILM: String = "The Quiet Projectionist"

    /** delphi LUX, Saal 4 ([SampleData] hall), drawn with [saal4Layout] in [store]. */
    const val HALL: Long = 504L

    /** The upcoming visit of [store]. */
    const val VISIT: Long = 7_001L

    /** Saal 4 for the tickets: 10 rows of 14 seats, ids row-major from 1. */
    val saal4Layout: SeatLayout = SeatLayout.fromPatterns(List(10) { "S".repeat(14) }, firstSeatId = 1)

    /** Row 7 · Seat 10 of [saal4Layout]: the visit's own seat. */
    val row7Seat10: SeatId = saal4Layout.rows[6].seatIds[9]

    /** The booking's seats: the visit's own seat first, then the seat next to it. */
    val seats: List<TicketSeat> = listOf(TicketSeat("7", "10"), TicketSeat("7", "11"))

    /** The showing's start: 20:15 Berlin time. */
    val START: LocalTime = LocalTime.of(20, 15)

    /** An invented 21 × 21 module grid (version 1), see the class comment. */
    fun qrModules(size: Int = 21): QrModules {
        var seed = 0x2F6E2B1L
        val noise = BooleanArray(size * size) {
            seed = (seed * 1_103_515_245L + 12_345L) and 0x7FFF_FFFFL
            (seed shr 16) and 1L == 1L
        }
        return QrModules.of(size) { row, column ->
            finderModule(row, column, size)
                ?: if (row == 6 || column == 6) (row + column) % 2 == 0 else noise[row * size + column]
        }
    }

    /** Dark/light inside a finder pattern or its light separator, `null` elsewhere. */
    private fun finderModule(row: Int, column: Int, size: Int): Boolean? {
        for ((top, left) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) {
            val r = row - top
            val c = column - left
            if (r in -1..7 && c in -1..7) {
                if (r !in 0..6 || c !in 0..6) return false // separator
                val ring = maxOf(abs(r - 3), abs(c - 3))
                return ring != 2
            }
        }
        return null
    }

    /** The ticket of [visitId] with [seats], with or without its QR code. */
    fun ticket(visitId: Long = VISIT, qr: QrModules? = qrModules()): Ticket = Ticket(visitId, BOOKING_NUMBER, seats, qr)

    /** The upcoming visit to the film at 20:15 on [date] (Berlin), seat Row 7 · Seat 10. */
    fun visit(date: LocalDate, id: Long = VISIT, startTime: LocalTime? = START): Visit = Visit(
        id = id,
        hallId = HALL,
        date = date,
        startTime = startTime,
        filmTitle = FILM,
        seatId = row7Seat10,
        screeningFormat = ScreeningFormat.OMU,
    )

    /** The date of the showing [daysAhead] days after [now] in Berlin. */
    fun dateAfter(now: Instant, daysAhead: Long): LocalDate = ShowingTime.today(now).plusDays(daysAhead)

    /**
     * [SampleData.store] with Saal 4 drawn as [saal4Layout], plus [visits] and their
     * [tickets] (by default the upcoming visit [daysAhead] days after [now] with its
     * ticket and QR code).
     */
    fun store(
        now: Instant = SampleProgram.FETCHED_AT,
        daysAhead: Long = 2,
        visits: List<Visit> = listOf(visit(dateAfter(now, daysAhead))),
        tickets: List<Ticket> = listOf(ticket()),
    ): FakeStore = SampleData.store().let { s ->
        s.copy(
            halls = s.halls.map { if (it.id == HALL) it.copy(layout = saal4Layout, screenWidth = 12f, screenDistance = 2f) else it },
            visits = s.visits + visits,
            tickets = s.tickets + tickets,
        )
    }
}
