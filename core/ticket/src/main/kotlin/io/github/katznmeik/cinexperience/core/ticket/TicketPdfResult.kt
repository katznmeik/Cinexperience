package io.github.katznmeik.cinexperience.core.ticket

import java.time.LocalDate
import java.time.LocalTime

/** What [TicketPdfReader] made of a file. */
sealed interface TicketPdfResult {
    /** A Yorck ticket: [content] says per field what was found. */
    data class Read(val content: TicketPdfContent) : TicketPdfResult

    /** Not readable: nothing from the file may be used. */
    data class Refused(val error: TicketPdfError) : TicketPdfResult
}

/** Why a file was refused. */
enum class TicketPdfError {
    /** Bigger than [TicketPdfLimits.MAX_FILE_BYTES]. */
    TOO_LARGE,

    /** Doesn't start like a PDF file. */
    NOT_A_PDF,

    /** A broken or cut-off structure: syntax errors, wrong offsets or lengths, a damaged stream. */
    DAMAGED,

    /**
     * A PDF feature outside the reader's scope: cross-reference streams, incremental
     * updates, object streams, other stream filters, external stream data, too many
     * entries in one array, dictionary or page.
     */
    UNSUPPORTED,

    /** The file is encrypted. */
    ENCRYPTED,

    /** More than [TicketPdfLimits.MAX_PAGES] pages. */
    TOO_MANY_PAGES,

    /** More than [TicketPdfLimits.MAX_OBJECTS] objects. */
    TOO_MANY_OBJECTS,

    /** A stream unpacks to more than [TicketPdfLimits.MAX_STREAM_BYTES], or all to more than [TicketPdfLimits.MAX_TOTAL_STREAM_BYTES]. */
    STREAM_TOO_LARGE,

    /** Objects, the page tree or graphics states nested deeper than the limits allow. */
    NESTING_TOO_DEEP,

    /** References that lead back to themselves (also a page listed twice in the page tree). */
    REFERENCE_CYCLE,

    /** More than [TicketPdfLimits.MAX_GLYPHS] glyphs. */
    TOO_MUCH_TEXT,

    /** A readable PDF that isn't recognisably a Yorck ticket. */
    NOT_A_TICKET,
}

/**
 * The fields of a ticket (spec §11.2), each checked like the program data (§11.1
 * rule 7) and cleaned of control and format characters. Nothing else is read out of
 * the file: not the "Gebucht von" name, prices, categories, transaction or ticket
 * numbers, the address or the conditions text.
 *
 * Several tickets of one booking (several "Ticket" blocks, e.g. one per page) must
 * agree on every field but the seats; the seats of all blocks are collected.
 */
data class TicketPdfContent(
    /** The "Buchungsnummer": [TicketPdfRules.isValidBookingNumber]. */
    val bookingNumber: TicketPdfField<String>,
    /** The bold line(s) after "Ticket". */
    val filmTitle: TicketPdfField<String>,
    /** The value after "Kino", e.g. "delphi LUX" (without the label). */
    val cinema: TicketPdfField<String>,
    /** "Datum" (dd.MM.yyyy). */
    val date: TicketPdfField<LocalDate>,
    /** "Zeit" (HH:mm, Berlin clock time; "Uhr" dropped). */
    val startTime: TicketPdfField<LocalTime>,
    /** The value after "Saal", e.g. "4" for "Saal 4" (without the label). */
    val hall: TicketPdfField<String>,
    /** Every seat of the booking as printed ("Reihe", "Platz"), in the ticket's order, without duplicates. */
    val seats: TicketPdfField<List<PrintedSeat>>,
    /** The QR code's checked module grid. */
    val qrCode: TicketPdfField<QrModuleGrid>,
)

/** One field of [TicketPdfContent]. */
sealed interface TicketPdfField<out T> {
    /** The value, checked and cleaned. */
    data class Found<out T>(val value: T) : TicketPdfField<T>

    /** No usable value, see [reason]; the field is left for manual entry. */
    data class NotFound(val reason: NotFoundReason) : TicketPdfField<Nothing>

    /** The value if [Found], else `null`. */
    val valueOrNull: T? get() = (this as? Found<T>)?.value
}

/** Why a field has no value. */
enum class NotFoundReason {
    /** The label (or the QR code image) isn't on the ticket. */
    MISSING,

    /** The label is there, but its value fails the checks (or the QR code image fails the grid checks). */
    INVALID,

    /** Different values (e.g. two blocks naming different halls), or a seat list the reader can't pair up. */
    AMBIGUOUS,
}

/** A seat as printed on the ticket: "Reihe" [row] (e.g. "7", "C", "R1"; "-" for an unnamed row) and "Platz" [seat]. */
data class PrintedSeat(val row: String, val seat: String)
