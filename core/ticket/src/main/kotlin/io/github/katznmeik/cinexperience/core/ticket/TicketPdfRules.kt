package io.github.katznmeik.cinexperience.core.ticket

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * The checks every value from a ticket has to pass (spec §11.2 rule 2, like the
 * program data, §11.1 rule 7). The raw values are already [TicketText.clean]ed;
 * a value that fails is not used, never repaired.
 */
object TicketPdfRules {
    const val MIN_BOOKING_NUMBER_LENGTH: Int = 4
    const val MAX_BOOKING_NUMBER_LENGTH: Int = 20
    const val MAX_TITLE_LENGTH: Int = 200
    const val MAX_CINEMA_LENGTH: Int = 80
    const val MAX_HALL_LENGTH: Int = 40
    const val MAX_ROW_LENGTH: Int = 8
    const val MAX_SEAT_LENGTH: Int = 4

    /** At most this many seats per booking; a longer list is left for manual entry. */
    const val MAX_SEATS: Int = 20

    /** Upper-case ASCII letters and digits, as QR codes encode them (Yorck's have 7). */
    private val BOOKING_NUMBER = Regex("[A-Z0-9]{$MIN_BOOKING_NUMBER_LENGTH,$MAX_BOOKING_NUMBER_LENGTH}")

    /** A cinema or hall name: letters and digits, with spaces and a little punctuation between them. */
    private val NAME = Regex("[\\p{L}\\p{Nd}](?:[\\p{L}\\p{Nd} .,'&/()\u2019-]*[\\p{L}\\p{Nd}.)])?")
    private val ROW = Regex("[\\p{L}\\p{Nd}]{1,$MAX_ROW_LENGTH}|-")
    private val SEAT = Regex("[\\p{L}\\p{Nd}]{1,$MAX_SEAT_LENGTH}")
    private val SEAT_RANGE = Regex("([0-9]{1,3}) ?[-\u2013] ?([0-9]{1,3})")
    private val DATE = Regex("([0-9]{2})\\.([0-9]{2})\\.([0-9]{4})")
    private val TIME = Regex("([0-9]{2}):([0-9]{2})(?: Uhr)?")
    private val YEARS = 2000..2099

    /** True for a booking number such as `TEST123`: [MIN_BOOKING_NUMBER_LENGTH] to [MAX_BOOKING_NUMBER_LENGTH] upper-case ASCII letters and digits. */
    fun isValidBookingNumber(text: String): Boolean = BOOKING_NUMBER.matches(text)

    internal fun bookingNumber(raw: String): String? = raw.takeIf(::isValidBookingNumber)

    /** Any cleaned text of 1 to [MAX_TITLE_LENGTH] characters. */
    internal fun title(raw: String): String? = raw.takeIf { TicketText.clean(it) == it && TicketText.length(it) in 1..MAX_TITLE_LENGTH }

    internal fun cinema(raw: String): String? = name(raw, MAX_CINEMA_LENGTH)

    internal fun hall(raw: String): String? = name(raw, MAX_HALL_LENGTH)

    /** `dd.MM.yyyy`, a real date in 2000–2099. */
    internal fun date(raw: String): LocalDate? {
        val match = DATE.matchEntire(raw) ?: return null
        val (day, month, year) = match.destructured
        if (year.toInt() !in YEARS) return null
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    /** `HH:mm` or `HH:mm Uhr`, 00:00 to 23:59. */
    internal fun time(raw: String): LocalTime? {
        val match = TIME.matchEntire(raw) ?: return null
        val (hour, minute) = match.destructured
        return try {
            LocalTime.of(hour.toInt(), minute.toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    /**
     * The seats of one ticket block: one row ("Reihe") and one seat, a comma-separated
     * list ("8, 9") or a range of numbers ("8–9", "8-10") as "Platz". Several rows, or
     * anything else, can't be paired up without guessing: [NotFoundReason.AMBIGUOUS]
     * or [NotFoundReason.INVALID].
     */
    internal fun seats(row: String?, seat: String?): TicketPdfField<List<PrintedSeat>> {
        if (row == null && seat == null) return TicketPdfField.NotFound(NotFoundReason.MISSING)
        if (row == null || seat == null) return TicketPdfField.NotFound(NotFoundReason.INVALID)
        if (',' in row || ';' in row) return TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)
        if (!ROW.matches(row)) return TicketPdfField.NotFound(NotFoundReason.INVALID)
        val seats = seatList(seat) ?: return TicketPdfField.NotFound(NotFoundReason.INVALID)
        if (seats.size > MAX_SEATS) return TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)
        return TicketPdfField.Found(seats.distinct().map { PrintedSeat(row, it) })
    }

    private fun seatList(raw: String): List<String>? {
        SEAT_RANGE.matchEntire(raw)?.let { match ->
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].toInt()
            if (first < 1 || last <= first || last - first >= MAX_SEATS) return null
            return (first..last).map(Int::toString)
        }
        val parts = raw.split(',').map(String::trim)
        if (parts.size > MAX_SEATS || parts.any { !SEAT.matches(it) }) return null
        return parts
    }

    private fun name(raw: String, maxLength: Int): String? =
        raw.takeIf { TicketText.length(it) in 1..maxLength && NAME.matches(it) }
}
