package io.github.katznmeik.cinexperience.core.model

import java.util.Base64

/**
 * The ticket of a visit (spec §4.7): at most one per visit, created by the ticket
 * import (§11.2) and deleted with its visit. The PDF itself, the booker's name,
 * prices, transaction and ticket numbers are never stored.
 *
 * @property visitId the visit the ticket belongs to; `0` for a visit that is
 *   saved together with it (see `TicketRepository.saveVisitWithTicket`).
 * @property bookingNumber Yorck's "Buchungsnummer": unique across tickets, so a
 *   ticket is recognised when it is imported again. The QR code contains it.
 * @property seats every seat of the booking as printed on the ticket, 1 to
 *   [MAX_SEATS], no seat twice. The visit's own seat is one of them.
 * @property qrModules the QR code as read from the PDF; `null` once it was removed
 *   (7 days after the showing, see [ShowingTime.qrModulesExpireAt]) or for a
 *   ticket restored from a backup, which never holds QR codes (§8).
 *
 * Behaves like a data class (equality by content, [copy]), except that it keeps
 * its own copy of [seats] and [toString] hides the booking number.
 */
class Ticket(
    val visitId: Long,
    val bookingNumber: BookingNumber,
    seats: List<TicketSeat>,
    val qrModules: QrModules? = null,
) {
    val seats: List<TicketSeat> = seats.toList()

    init {
        require(this.seats.size in 1..MAX_SEATS) { "A ticket has 1 to $MAX_SEATS seats, not ${this.seats.size}" }
        require(this.seats.toSet().size == this.seats.size) { "A ticket lists every seat once" }
    }

    fun copy(
        visitId: Long = this.visitId,
        bookingNumber: BookingNumber = this.bookingNumber,
        seats: List<TicketSeat> = this.seats,
        qrModules: QrModules? = this.qrModules,
    ): Ticket = Ticket(visitId, bookingNumber, seats, qrModules)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is Ticket &&
            visitId == other.visitId &&
            bookingNumber == other.bookingNumber &&
            seats == other.seats &&
            qrModules == other.qrModules

    override fun hashCode(): Int = listOf(visitId, bookingNumber, seats, qrModules).hashCode()

    /** Without the booking number (see [BookingNumber.toString]) and the QR code's modules. */
    override fun toString(): String = "Ticket(visitId=$visitId, bookingNumber=$bookingNumber, seats=$seats, qrModules=$qrModules)"

    companion object {
        /** Most seats of one booking: far above a real booking, small enough for any list. */
        const val MAX_SEATS: Int = 20
    }
}

/**
 * Yorck's booking number ("Buchungsnummer", spec §4.7, §11.2): [MIN_LENGTH] to
 * [MAX_LENGTH] characters, only `A`–`Z` and `0`–`9` (checked when it is created).
 * Yorck's are 7 characters long. [MAX_LENGTH] is a design choice with room to
 * spare, not a QR code limit (a version 1 code alone holds 7 to 25 such
 * characters, depending on its mode and error correction level).
 *
 * The booking number is what the QR code holds, so [toString] hides it (for logs
 * and messages); use [value] for the text itself.
 */
@JvmInline
value class BookingNumber(val value: String) {
    init {
        require(isValid(value)) { "A booking number has $MIN_LENGTH to $MAX_LENGTH characters A–Z and 0–9" }
    }

    override fun toString(): String = "BookingNumber(hidden)"

    companion object {
        const val MIN_LENGTH: Int = 4
        const val MAX_LENGTH: Int = 16

        /** Whether [text] is a valid booking number, exactly as it would be stored. */
        fun isValid(text: String): Boolean =
            text.length in MIN_LENGTH..MAX_LENGTH && text.all { it in 'A'..'Z' || it in '0'..'9' }

        /** The booking number [text], or `null` if it isn't valid. */
        fun ofOrNull(text: String): BookingNumber? = if (isValid(text)) BookingNumber(text) else null
    }
}

/**
 * One seat of a booking as printed on the ticket (spec §4.7), e.g. row "7",
 * seat "10". The texts follow the rules of the app's own labels, so the import
 * can match them with `SeatLayout.labels()`:
 *
 * @property row the row label, like [LayoutRow.label]: not blank, at most
 *   [TextLimits.MAX_ROW_LABEL_LENGTH] code points, without surrounding spaces,
 *   control or format characters. `null` = the ticket names no row (a row
 *   without a name, spec §4.3; never [LayoutRow.NO_NAME], so texts read
 *   "Seat 12" as for [SeatLabel.row]).
 * @property seat the seat, like [SeatLabel.seat]: a seat number (digits of a
 *   non-negative number without leading zeros, as `labels()` writes it) or a
 *   [LayoutCell.Seat.fixedLabel] ("R").
 */
data class TicketSeat(val row: String?, val seat: String) {
    init {
        require(row == null || isValidRow(row)) {
            "A ticket's row has 1 to ${TextLimits.MAX_ROW_LABEL_LENGTH} characters, without surrounding spaces, " +
                "control or format characters, and is not \"${LayoutRow.NO_NAME}\""
        }
        require(isValidSeat(seat)) { "A ticket's seat is a seat number or a fixed seat label" }
    }

    /** The seat as a [SeatLabel], for the app's texts ("Row 7 · Seat 10"); the kind isn't on the ticket. */
    val label: SeatLabel get() = SeatLabel(row, seat)

    companion object {
        /** Whether [row] is a valid [TicketSeat.row] (see there). */
        fun isValidRow(row: String): Boolean =
            row != LayoutRow.NO_NAME && TextLimits.fits(row, TextLimits.MAX_ROW_LABEL_LENGTH) && isCleanLabel(row)

        /** Whether [seat] is a valid [TicketSeat.seat] (see there). */
        fun isValidSeat(seat: String): Boolean = LayoutCell.Seat.isValidFixedLabel(seat) || isSeatNumber(seat)

        /** The seat [row] / [seat], or `null` if either isn't valid (see the class comment). */
        fun ofOrNull(row: String?, seat: String): TicketSeat? =
            if ((row == null || isValidRow(row)) && isValidSeat(seat)) TicketSeat(row, seat) else null

        private fun isSeatNumber(text: String): Boolean = text.toIntOrNull()?.let { it >= 0 && it.toString() == text } == true

        /** Not blank, no surrounding spaces, no ISO control or format (`Cf`) characters, like a fixed seat label. */
        private fun isCleanLabel(text: String): Boolean =
            text.isNotBlank() &&
                text == text.trim() &&
                text.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    }
}

/**
 * The modules of a ticket's QR code (spec §4.7, §11.2): a square grid of dark and
 * light modules, [size] × [size], for QR versions 1 to 4 ([SIZES]). The app never
 * decodes or generates QR codes; the ticket screen redraws this grid. Two grids
 * are equal when they have the same modules.
 *
 * Stored as text with [encode] / [decode]: `"<size>:<base64>"`, e.g. `"21:…"`,
 * where the Base64 (standard alphabet, padded) holds the modules row by row from
 * the top left, 8 per byte with the first module in the highest bit, dark = 1,
 * and the unused bits of the last byte 0. A version 1 code takes 3 + 76 chars.
 *
 * @param modules row by row from the top left, `true` = dark; copied.
 */
class QrModules(val size: Int, modules: BooleanArray) {
    private val modules: BooleanArray = modules.copyOf()

    init {
        require(size in SIZES) { "A QR code of versions 1–4 has 21, 25, 29 or 33 modules per side, not $size" }
        require(modules.size == size * size) { "A $size × $size QR code has ${size * size} modules, not ${modules.size}" }
    }

    /** The QR version, 1 to 4. */
    val version: Int get() = (size - 17) / 4

    /** Whether the module in [row] and [column] (from the top left, 0-based) is dark. */
    fun isDark(row: Int, column: Int): Boolean {
        require(row in 0 until size && column in 0 until size) { "No module ($row, $column) in a $size × $size code" }
        return modules[row * size + column]
    }

    /** The modules as text, see the class comment; [decode] reads it back. */
    fun encode(): String {
        val bytes = ByteArray(byteCount(size))
        for (i in modules.indices) {
            if (modules[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        return "$size$SEPARATOR${Base64.getEncoder().encodeToString(bytes)}"
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QrModules && size == other.size && modules.contentEquals(other.modules)

    override fun hashCode(): Int = 31 * size + modules.contentHashCode()

    /** Only the size: the modules are the booking number, which doesn't belong in logs. */
    override fun toString(): String = "QrModules($size × $size)"

    companion object {
        /** Modules per side of QR versions 1 to 4. */
        val SIZES: List<Int> = listOf(21, 25, 29, 33)

        private const val SEPARATOR = ':'

        /** Longest [encode] text (version 4): the decoder refuses anything longer before looking at it. */
        val MAX_ENCODED_LENGTH: Int = encodedLength(SIZES.last())

        /** A grid of [size] × [size] modules, `true` = dark. */
        fun of(size: Int, isDark: (row: Int, column: Int) -> Boolean): QrModules {
            require(size in SIZES) { "A QR code of versions 1–4 has 21, 25, 29 or 33 modules per side, not $size" }
            return QrModules(size, BooleanArray(size * size) { isDark(it / size, it % size) })
        }

        /**
         * Reads a text written by [encode]. Strict: anything else (another size,
         * missing or extra characters, other Base64 forms, unused bits set) is
         * refused with an IllegalArgumentException.
         */
        fun decode(text: String): QrModules {
            require(text.length <= MAX_ENCODED_LENGTH) { "Not a QR code text: too long" }
            val separator = text.indexOf(SEPARATOR)
            require(separator > 0) { "Not a QR code text: no size" }
            val size = SIZES.firstOrNull { it.toString() == text.substring(0, separator) }
            requireNotNull(size) { "Not a QR code text: unknown size" }
            require(text.length == encodedLength(size)) { "Not a QR code text: wrong length" }
            val base64 = text.substring(separator + 1)
            val bytes = try {
                Base64.getDecoder().decode(base64)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Not a QR code text: invalid Base64", e)
            }
            require(bytes.size == byteCount(size)) { "Not a QR code text: wrong number of modules" }
            // The canonical form only: e.g. no stray bits in the last Base64 character.
            require(Base64.getEncoder().encodeToString(bytes) == base64) { "Not a QR code text: not canonical" }
            val count = size * size
            for (i in count until bytes.size * 8) {
                require(bytes[i / 8].toInt() and (0x80 ushr (i % 8)) == 0) { "Not a QR code text: unused bits set" }
            }
            return QrModules(size, BooleanArray(count) { i -> bytes[i / 8].toInt() and (0x80 ushr (i % 8)) != 0 })
        }

        /** [decode], or `null` for a text it refuses. */
        fun decodeOrNull(text: String): QrModules? = try {
            decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun byteCount(size: Int): Int = (size * size + 7) / 8

        private fun encodedLength(size: Int): Int = size.toString().length + 1 + (byteCount(size) + 2) / 3 * 4
    }
}
