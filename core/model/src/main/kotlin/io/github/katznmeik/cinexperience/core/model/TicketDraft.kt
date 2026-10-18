package io.github.katznmeik.cinexperience.core.model

/**
 * A ticket read from a ticket PDF and not stored yet (spec §6.10): Add visit holds
 * it until Save, which stores it with the new visit as its [Ticket]; Back drops it.
 *
 * @property seats every seat of the booking as printed, in the ticket's order, at
 *   most [Ticket.MAX_SEATS], none twice. Empty when the ticket's seats couldn't be
 *   read; Save then takes the seat picked in the form ([toTicket]).
 * @property qrModules the QR code's modules, `null` if none could be read.
 *
 * Its text ([toString]) hides the booking number and the modules (see
 * [BookingNumber.toString], [QrModules.toString]).
 */
data class TicketDraft(
    val bookingNumber: BookingNumber,
    val seats: List<TicketSeat>,
    val qrModules: QrModules? = null,
) {
    init {
        require(seats.size <= Ticket.MAX_SEATS) { "A ticket has at most ${Ticket.MAX_SEATS} seats, not ${seats.size}" }
        require(seats.toSet().size == seats.size) { "A ticket lists every seat once" }
    }

    /**
     * The ticket to store with a new visit (`visitId` 0, see `TicketRepository.saveVisitWithTicket`):
     * the draft's seats, or, if none were read, [ownSeat] (the seat picked in the form).
     * `null` if there is no seat at all: a [Ticket] has at least one.
     *
     * @param qrModules the QR code to store, by default the draft's (pass `null` to drop
     *   one that has expired, [ShowingTime.qrModulesExpired]).
     */
    fun toTicket(ownSeat: TicketSeat?, qrModules: QrModules? = this.qrModules): Ticket? {
        val ticketSeats = seats.ifEmpty { listOfNotNull(ownSeat) }
        if (ticketSeats.isEmpty()) return null
        return Ticket(visitId = 0, bookingNumber = bookingNumber, seats = ticketSeats, qrModules = qrModules)
    }
}

/**
 * The seat a ticket names as [seat] (spec §11.2, Matching): the one seat of this
 * layout whose row label and seat text are exactly the printed ones (a row without
 * a name, `null`, matches only rows without a name; a fixed label like "R" only that
 * label). `null` if no seat has that label, or several do (labels need not be unique,
 * e.g. numbers restarting in every block): then nothing is guessed.
 */
fun LayoutLabels.seatOf(seat: TicketSeat): SeatId? {
    var found: SeatId? = null
    for (info in seats.values) {
        if (info.label.row == seat.row && info.label.seat == seat.seat) {
            if (found != null) return null
            found = info.id
        }
    }
    return found
}

/**
 * This seat as a ticket's seat ([TicketSeat]): its row label and its number or fixed
 * label; `null` if they don't fit [TicketSeat]'s rules (only a layout from an older
 * build could hold such a label).
 */
fun SeatLabel.toTicketSeatOrNull(): TicketSeat? = TicketSeat.ofOrNull(row, seat)
