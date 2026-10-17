package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.text.TextLine
import io.github.katznmeik.cinexperience.core.ticket.text.TextSegment
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max

/** The text fields of a ticket; [TicketPdfContent] without the QR code. */
internal class TicketTextFields(
    val bookingNumber: TicketPdfField<String>,
    val filmTitle: TicketPdfField<String>,
    val cinema: TicketPdfField<String>,
    val date: TicketPdfField<LocalDate>,
    val startTime: TicketPdfField<LocalTime>,
    val hall: TicketPdfField<String>,
    val seats: TicketPdfField<List<PrintedSeat>>,
) {
    val anyFound: Boolean
        get() = listOf(bookingNumber, filmTitle, cinema, date, startTime, hall, seats).any { it is TicketPdfField.Found }
}

/**
 * Finds the fields in the pages' lines (spec §11.2). Yorck's ticket has:
 * - the "Buchungsnummer" label with its value on the next line in the same column;
 * - a ticket block: "Ticket", the film title in bold on the line(s) below, then label
 *   and value on one line each: "Kino …", "Datum dd.MM.yyyy", "Zeit HH:mm Uhr",
 *   "Saal …", "Reihe …", "Platz …" (the last three side by side), then
 *   "Kat. …" and "Gebucht von …", which close the block and are never read.
 *
 * Each label is accepted with its value on the same line or on the next line in the
 * same column, and with or without a colon. A block covers the segments below
 * "Ticket" from its column rightwards, up to the next "Ticket" below it or the
 * "Gebucht von" line; the first occurrence of each label in a block counts.
 * Several blocks (e.g. one page per ticket) must agree on all fields but the seats,
 * whose lists are joined (if they disagree, the seats are [NotFoundReason.AMBIGUOUS]
 * too). Two blocks side by side on one line can't be told apart and give
 * [NotFoundReason.AMBIGUOUS] fields.
 */
internal object TicketFields {
    private const val BOOKING_NUMBER = "Buchungsnummer"
    private const val TICKET = "Ticket"
    private const val CINEMA = "Kino"
    private const val DATE = "Datum"
    private const val TIME = "Zeit"
    private const val HALL = "Saal"
    private const val ROW = "Reihe"
    private const val SEAT = "Platz"
    private const val BOOKED_BY = "Gebucht von"
    private val BLOCK_LABELS = listOf(CINEMA, DATE, TIME, HALL, ROW, SEAT)

    /** Film title lines at most. */
    private const val MAX_TITLE_LINES = 3

    /**
     * More "Ticket" blocks on one page than fit on an A3 ticket page can't be told
     * apart (their fields are [NotFoundReason.AMBIGUOUS]); this also bounds the work.
     */
    private const val MAX_BLOCKS_PER_PAGE = 4

    /** Only the first occurrences of the booking number's label are looked at (Yorck's has one, the conditions one more). */
    private const val MAX_BOOKING_LABELS = 16

    /** A value on the next line is at most this many font sizes below its label. */
    private const val NEXT_LINE_DISTANCE = 3.0

    /** A title line is at most this many font sizes below the line above it. */
    private const val TITLE_LINE_DISTANCE = 2.5

    /** Segments start in the same column if their x differ by at most this share of the font size… */
    private const val COLUMN_TOLERANCE = 0.5

    /** …or this many points. */
    private const val MIN_COLUMN_TOLERANCE = 2.0

    /** A segment and the index of its line on its page (in reading order). */
    private class Placed(val line: Int, val segment: TextSegment) {
        val text: String get() = segment.text
    }

    private class Block(
        val title: TicketPdfField<String>,
        val cinema: TicketPdfField<String>,
        val date: TicketPdfField<LocalDate>,
        val time: TicketPdfField<LocalTime>,
        val hall: TicketPdfField<String>,
        val seats: TicketPdfField<List<PrintedSeat>>,
    ) {
        /** No label at all below "Ticket": the word stands somewhere else, this is no ticket block. */
        val hasNoLabels: Boolean
            get() = listOf(cinema, date, time, hall, seats).all { it == TicketPdfField.NotFound(NotFoundReason.MISSING) }
    }

    /** The fields, or `null` if the pages aren't recognisably a Yorck ticket. */
    fun read(pages: List<List<TextLine>>): TicketTextFields? {
        val placedPages = pages.map { lines ->
            lines.flatMapIndexed { line, textLine -> textLine.segments.map { Placed(line, it) } }
        }
        val bookingValues = placedPages.asSequence()
            .flatMap { page -> page.asSequence().mapNotNull { labelValue(BOOKING_NUMBER, it, page) } }
            .take(MAX_BOOKING_LABELS)
            .toList()
        val blocks = placedPages.flatMap(::blocks).filterNot { it.hasNoLabels }
        if (bookingValues.isEmpty() && blocks.isEmpty()) return null

        val title = choose(blocks.map { it.title })
        val cinema = choose(blocks.map { it.cinema })
        val date = choose(blocks.map { it.date })
        val time = choose(blocks.map { it.time })
        val hall = choose(blocks.map { it.hall })
        // Blocks of different showings: their seats can't be joined either.
        val sameShowing = listOf(title, cinema, date, time, hall).none { it == TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS) }
        val fields = TicketTextFields(
            bookingNumber = choose(bookingValues.map { value -> checked(value, TicketPdfRules::bookingNumber) }, ignoreInvalid = true),
            filmTitle = title,
            cinema = cinema,
            date = date,
            startTime = time,
            hall = hall,
            seats = if (sameShowing) joinSeats(blocks.map { it.seats }) else TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS),
        )
        return fields.takeIf { it.anyFound }
    }

    private fun blocks(page: List<Placed>): List<Block> {
        val anchors = page.filter { it.text == TICKET }
        if (anchors.size > MAX_BLOCKS_PER_PAGE) return listOf(ambiguousBlock())
        return anchors.map { anchor ->
            if (anchors.any { it !== anchor && it.line == anchor.line }) return@map ambiguousBlock()
            val left = anchor.segment.x - tolerance(anchor.segment)
            val nextAnchorLine = anchors.filter { it.line > anchor.line }.minOfOrNull { it.line } ?: Int.MAX_VALUE
            val below = page.filter { it.line > anchor.line && it.line < nextAnchorLine && it.segment.x >= left }
            val end = below.firstOrNull { it.text == BOOKED_BY || it.text.startsWith("$BOOKED_BY ") }?.line ?: Int.MAX_VALUE
            block(anchor, below.filter { it.line < end }, page)
        }
    }

    private fun block(anchor: Placed, segments: List<Placed>, page: List<Placed>): Block {
        // A label without a value is "" (invalid), a missing label null.
        fun field(label: String): String? = segments.firstNotNullOfOrNull { labelValue(label, it, page) }?.let { it.value ?: "" }
        fun <T> checkedField(label: String, check: (String) -> T?): TicketPdfField<T> =
            segments.firstNotNullOfOrNull { labelValue(label, it, page) }?.let { checked(it, check) }
                ?: TicketPdfField.NotFound(NotFoundReason.MISSING)
        return Block(
            title = title(anchor, segments),
            cinema = checkedField(CINEMA, TicketPdfRules::cinema),
            date = checkedField(DATE, TicketPdfRules::date),
            time = checkedField(TIME, TicketPdfRules::time),
            hall = checkedField(HALL, TicketPdfRules::hall),
            seats = TicketPdfRules.seats(field(ROW), field(SEAT)),
        )
    }

    /** The bold line(s) right below "Ticket" in its column, up to the first line with a label. */
    private fun title(anchor: Placed, segments: List<Placed>): TicketPdfField<String> {
        val lines = segments.groupBy { it.line }.toSortedMap().values
        val parts = ArrayList<String>()
        var above = anchor.segment
        for (line in lines) {
            val first = line.first().segment
            val isTitleLine = line.size == 1 && first.isBold && sameColumn(first, anchor.segment) &&
                above.y - first.y <= TITLE_LINE_DISTANCE * max(above.size, first.size) &&
                BLOCK_LABELS.none { first.text == it || first.text.startsWith("$it ") || first.text.startsWith("$it:") }
            if (!isTitleLine) break
            parts += first.text
            above = first
            if (parts.size > MAX_TITLE_LINES) return TicketPdfField.NotFound(NotFoundReason.INVALID)
        }
        if (parts.isEmpty()) return TicketPdfField.NotFound(NotFoundReason.MISSING)
        return checked(LabelValue(parts.joinToString(" ")), TicketPdfRules::title)
    }

    /** A label found; [value] is `null` if nothing follows it. */
    private class LabelValue(val value: String?)

    /** The value of [label] if [placed] starts with it, else `null`. */
    private fun labelValue(label: String, placed: Placed, page: List<Placed>): LabelValue? {
        val text = placed.text
        return when {
            text == label || text == "$label:" -> LabelValue(valueBelow(placed, page))
            text.startsWith("$label: ") -> LabelValue(text.substring(label.length + 2))
            text.startsWith("$label ") -> LabelValue(text.substring(label.length + 1))
            else -> null
        }
    }

    /** The first segment below [label] in its column, close enough to belong to it. */
    private fun valueBelow(label: Placed, page: List<Placed>): String? {
        val below = page.firstOrNull { it.line > label.line && sameColumn(it.segment, label.segment) } ?: return null
        val distance = label.segment.y - below.segment.y
        return below.text.takeIf { distance > 0 && distance <= NEXT_LINE_DISTANCE * max(label.segment.size, below.segment.size) }
    }

    private fun sameColumn(a: TextSegment, b: TextSegment): Boolean = abs(a.x - b.x) <= tolerance(b)

    private fun tolerance(segment: TextSegment): Double = max(MIN_COLUMN_TOLERANCE, COLUMN_TOLERANCE * segment.size)

    private fun <T> checked(value: LabelValue, check: (String) -> T?): TicketPdfField<T> {
        val raw = value.value ?: return TicketPdfField.NotFound(NotFoundReason.INVALID)
        return check(raw)?.let { TicketPdfField.Found(it) } ?: TicketPdfField.NotFound(NotFoundReason.INVALID)
    }

    /**
     * One value from several occurrences (blocks): the value if all that have one
     * agree, [NotFoundReason.AMBIGUOUS] if they differ. An invalid occurrence next to
     * a valid one makes it [NotFoundReason.INVALID] (something is off; left for manual
     * entry) unless [ignoreInvalid]: the booking number's label also appears in other
     * text. Missing occurrences don't count.
     */
    private fun <T> choose(fields: List<TicketPdfField<T>>, ignoreInvalid: Boolean = false): TicketPdfField<T> {
        val values = fields.filterIsInstance<TicketPdfField.Found<T>>().map { it.value }.distinct()
        val reasons = fields.filterIsInstance<TicketPdfField.NotFound>().map { it.reason }
        return when {
            values.size > 1 || NotFoundReason.AMBIGUOUS in reasons -> TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)
            values.size == 1 && (ignoreInvalid || NotFoundReason.INVALID !in reasons) -> TicketPdfField.Found(values.single())
            values.size == 1 || NotFoundReason.INVALID in reasons -> TicketPdfField.NotFound(NotFoundReason.INVALID)
            else -> TicketPdfField.NotFound(NotFoundReason.MISSING)
        }
    }

    /** All blocks' seats in order, or the reason if any block's seats are unknown. */
    private fun joinSeats(fields: List<TicketPdfField<List<PrintedSeat>>>): TicketPdfField<List<PrintedSeat>> {
        if (fields.isEmpty()) return TicketPdfField.NotFound(NotFoundReason.MISSING)
        val reasons = fields.filterIsInstance<TicketPdfField.NotFound>().map { it.reason }
        if (reasons.isNotEmpty()) {
            return TicketPdfField.NotFound(
                when {
                    NotFoundReason.AMBIGUOUS in reasons -> NotFoundReason.AMBIGUOUS
                    NotFoundReason.INVALID in reasons || reasons.size < fields.size -> NotFoundReason.INVALID
                    else -> NotFoundReason.MISSING
                },
            )
        }
        val seats = fields.flatMap { (it as TicketPdfField.Found).value }.distinct()
        if (seats.size > TicketPdfRules.MAX_SEATS) return TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)
        return TicketPdfField.Found(seats)
    }

    private fun ambiguousBlock(): Block {
        val ambiguous = TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)
        return Block(ambiguous, ambiguous, ambiguous, ambiguous, ambiguous, ambiguous)
    }
}
