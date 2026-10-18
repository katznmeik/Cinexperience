package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * The checks every value from a ticket has to pass (spec §11.2 rule 2, like the
 * program data, §11.1 rule 7). The raw values are already [TicketText.clean]ed;
 * a value that fails is not used, never repaired.
 */
object TicketPdfRules {
    const val MAX_TITLE_LENGTH: Int = 200
    const val MAX_CINEMA_LENGTH: Int = 80
    const val MAX_HALL_LENGTH: Int = 40
    const val MAX_ROW_LENGTH: Int = 8

    /** At most this many seats per booking; a longer list is left for manual entry. */
    const val MAX_SEATS: Int = 20

    /** A cinema or hall name: letters and digits, with spaces and a little punctuation between them. */
    private val NAME = Regex("[\\p{L}\\p{Nd}](?:[\\p{L}\\p{Nd} .,'&/()\u2019-]*[\\p{L}\\p{Nd}.)])?")

    /** A row label: ASCII letters and digits ("7", "C", "R1"); "-" is a row without a name. */
    private val ROW = Regex("[A-Za-z0-9]{1,$MAX_ROW_LENGTH}")
    private const val UNNAMED_ROW = "-"

    /** A seat number as the app's layouts write it: ASCII digits without leading zeros. */
    private val SEAT_NUMBER = Regex("0|[1-9][0-9]{0,3}")

    /** A fixed seat label ("R"): 1 to 4 ASCII letters and digits with at least one letter. */
    private val FIXED_SEAT_LABEL = Regex("(?=[0-9]*[A-Za-z])[A-Za-z0-9]{1,4}")
    private val SEAT_RANGE = Regex("([1-9][0-9]{0,2}) ?[-\u2013] ?([1-9][0-9]{0,2})")
    private val DATE = Regex("([0-9]{2})\\.([0-9]{2})\\.([0-9]{4})")
    private val TIME = Regex("([0-9]{2}):([0-9]{2})(?: Uhr)?")
    private val YEARS = 2000..2099

    /**
     * True for a booking number such as `TEST123`, by the one rule the app stores them
     * with (core:model's `BookingNumber`: upper-case ASCII letters and digits, 4 to 16).
     */
    fun isValidBookingNumber(text: String): Boolean = BookingNumber.isValid(text)

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
     * list ("10, 11") or a range of numbers ("10–11", "10-12") as "Platz", at most
     * [MAX_SEATS]. Several rows, or anything else, can't be paired up without
     * guessing: [NotFoundReason.AMBIGUOUS] or [NotFoundReason.INVALID]. A row printed
     * as "-" (no name) becomes `null`.
     */
    internal fun seats(row: String?, seat: String?): TicketPdfField<List<PrintedSeat>> {
        if (row == null && seat == null) return TicketPdfField.NotFound(NotFoundReason.MISSING)
        if (row == null || seat == null) return TicketPdfField.NotFound(NotFoundReason.INVALID)
        if (',' in row || ';' in row) return TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)
        val printedRow = when {
            row == UNNAMED_ROW -> null
            ROW.matches(row) && TicketSeat.isValidRow(row) -> row
            else -> return TicketPdfField.NotFound(NotFoundReason.INVALID)
        }
        val seats = seatList(seat) ?: return TicketPdfField.NotFound(NotFoundReason.INVALID)
        return TicketPdfField.Found(seats.distinct().map { PrintedSeat(printedRow, it) })
    }

    /** At most [MAX_SEATS] seats that core:model's `TicketSeat` accepts, or `null`. */
    private fun seatList(raw: String): List<String>? {
        SEAT_RANGE.matchEntire(raw)?.let { match ->
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].toInt()
            if (last <= first || last - first >= MAX_SEATS) return null
            return (first..last).map(Int::toString)
        }
        val parts = raw.split(',').map(String::trim)
        if (parts.size > MAX_SEATS || parts.any { !isValidSeat(it) }) return null
        return parts
    }

    private fun isValidSeat(seat: String): Boolean =
        (SEAT_NUMBER.matches(seat) || FIXED_SEAT_LABEL.matches(seat)) && TicketSeat.isValidSeat(seat)

    private fun name(raw: String, maxLength: Int): String? =
        raw.takeIf { TicketText.length(it) in 1..maxLength && NAME.matches(it) }
}
