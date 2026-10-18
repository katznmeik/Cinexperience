package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.text.TextLine
import io.github.katznmeik.cinexperience.core.ticket.text.TextSegment
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.TreeMap
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
 * - the "Buchungsnummer" label left of the ticket block, with its value on the next
 *   line in the same column;
 * - a ticket block: "Ticket", the film title in bold on the line(s) below, then label
 *   and value on one line each, all regular: "Kino …", "Datum dd.MM.yyyy",
 *   "Zeit HH:mm Uhr", "Saal …", "Reihe …", "Platz …" (the last three side by side),
 *   then "Kat. …" and the bold "Gebucht von …", which close the block and are never read.
 *
 * The booker's name must never become a field. A booker segment has "gebucht" in its
 * letters, whatever the case, spaces or format characters ("GEBUCHT VON",
 * "Gebucht von:", "gebuchtvon"). A block covers the segments below "Ticket" from its
 * column rightwards, up to the next "Ticket" below it. The title comes first: the bold
 * lines right below "Ticket" (labels are regular, so a title may start with "Zeit" or
 * end in "Kino"; it may be "Ausgebucht", but a line that starts with "gebucht" or
 * says "gebucht von" is no title line). After the title, the first line with a booker
 * segment anywhere on the page and a segment in the block closes the block (so also
 * a "Gebucht von" left of the block's column); nothing from that line on is ever
 * looked at. A note mentioning "gebucht" left of the block, with nothing of the block
 * on its line, closes nothing. The labels are looked for only below the title and
 * above the next bold line (the booker's name is bold, no label or value is), each
 * with its value on the same line or on the next line in its column within the block,
 * with or without a colon; the first occurrence of each label counts. No value may be
 * a label line, mention "gebucht" or (but the title) start like a category ("Kat",
 * "Kat.1", "Kategorie"). A value from the line below its label is refused if its own
 * line or its label's line has a booker segment, or if the next line below it, or the
 * next segment below it in its column, is a booker line, however far down.
 *
 * The booking number is looked for only outside the ticket blocks (each block's
 * area reaching down to the next "Ticket" or the page's end): in Yorck's left
 * column, label and value in one column; only booker segments outside the blocks
 * count for it, so the block's own "Gebucht von" line never refuses it.
 *
 * Several blocks (e.g. one page per ticket) must agree on all fields but the seats;
 * their seats are joined only if every block has the same title, cinema, date, time
 * and hall, else they are [NotFoundReason.AMBIGUOUS]. Two blocks side by side on one
 * line can't be told apart and give [NotFoundReason.AMBIGUOUS] fields.
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

    /** In the letters of a booker line ("Gebucht von …"), in lower case. */
    private const val BOOKED = "gebucht"

    /** The letters of "Gebucht von", in lower case. */
    private const val BOOKED_BY = "gebuchtvon"

    /** Texts that start a label line: such a line is never another label's value. */
    private val LABELS = listOf(BOOKING_NUMBER, TICKET, CINEMA, DATE, TIME, HALL, ROW, SEAT)

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

    /** A page's segments in reading order, and the booker segments among them. */
    private class Page(val segments: List<Placed>) {
        val bookers: List<Placed> = segments.filter { isBookerLine(it.text) }
    }

    /**
     * Where a value is looked for: [segments], and the booker segments that count for
     * it ([bookers], in reading order; they may lie outside [segments], e.g. the line
     * that closed a block).
     */
    private class Scope(val segments: List<Placed>, val bookers: List<Placed>)

    /** A ticket block's area: from its anchor's line down to the next anchor's, from its column rightwards. */
    private class Area(val firstLine: Int, val endLine: Int, val left: Double) {
        operator fun contains(placed: Placed): Boolean = placed.line in firstLine until endLine && placed.segment.x >= left
    }

    /**
     * All block areas of a page, however many anchors it has: they don't overlap, so a
     * segment can only be in the area of the last anchor line at or above it (from the
     * leftmost anchor's column, if a line has several).
     */
    private class Areas(anchors: List<Placed>) {
        private val leftByLine = TreeMap<Int, Double>()

        init {
            for (anchor in anchors) leftByLine.merge(anchor.line, anchor.segment.x - tolerance(anchor.segment)) { a, b -> minOf(a, b) }
        }

        operator fun contains(placed: Placed): Boolean {
            val left = leftByLine.floorEntry(placed.line)?.value ?: return false
            return placed.segment.x >= left
        }
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
            Page(lines.flatMapIndexed { line, textLine -> textLine.segments.map { Placed(line, it) } })
        }
        val bookingValues = placedPages.asSequence()
            .flatMap { page ->
                val outside = outsideBlocks(page)
                val scope = Scope(outside, outside.filter { isBookerLine(it.text) })
                outside.asSequence().mapNotNull { labelValue(BOOKING_NUMBER, it, scope) }
            }
            .take(MAX_BOOKING_LABELS)
            .toList()
        val blocks = placedPages.flatMap(::blocks).filterNot { it.hasNoLabels }
        if (bookingValues.isEmpty() && blocks.isEmpty()) return null

        val title = choose(blocks.map { it.title })
        val cinema = choose(blocks.map { it.cinema })
        val date = choose(blocks.map { it.date })
        val time = choose(blocks.map { it.time })
        val hall = choose(blocks.map { it.hall })
        // Seats of several blocks belong together only if every block names the same showing in full.
        val showings = blocks.map { listOf(it.title, it.cinema, it.date, it.time, it.hall) }
        val sameShowing = blocks.size <= 1 || showings.all { fields -> fields.all { it is TicketPdfField.Found } } && showings.distinct().size == 1
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

    private fun anchors(page: Page): List<Placed> = page.segments.filter { it.text == TICKET }

    private fun area(anchor: Placed, anchors: List<Placed>): Area {
        val nextAnchorLine = anchors.filter { it.line > anchor.line }.minOfOrNull { it.line } ?: Int.MAX_VALUE
        return Area(anchor.line, nextAnchorLine, anchor.segment.x - tolerance(anchor.segment))
    }

    /** The page's segments outside every ticket block's area. */
    private fun outsideBlocks(page: Page): List<Placed> {
        val areas = Areas(anchors(page))
        return page.segments.filterNot { it in areas }
    }

    private fun blocks(page: Page): List<Block> {
        val anchors = anchors(page)
        if (anchors.size > MAX_BLOCKS_PER_PAGE) return listOf(ambiguousBlock())
        return anchors.map { anchor ->
            if (anchors.any { it !== anchor && it.line == anchor.line }) return@map ambiguousBlock()
            val area = area(anchor, anchors)
            block(anchor, page.segments.filter { it in area && it.line > anchor.line }, page)
        }
    }

    /** The fields of one block; [inArea] are the segments of its area below "Ticket", values are looked for only there. */
    private fun block(anchor: Placed, inArea: List<Placed>, page: Page): Block {
        val (title, titleEnd) = title(anchor, inArea)
        // After the title, the first booker line that reaches into the block closes it,
        // wherever its booker segment starts: nothing from it on is ever looked at.
        val areaLines = inArea.mapTo(HashSet()) { it.line }
        val end = page.bookers.firstOrNull { it.line > titleEnd && it.line in areaLines }?.line ?: Int.MAX_VALUE
        val segments = inArea.filter { it.line < end }
        // The booker's name is bold, no label or value is: the first bold line after the title ends the labels.
        val boldLine = segments.firstOrNull { it.line > titleEnd && it.segment.isBold }?.line ?: Int.MAX_VALUE
        val labelled = segments.filter { it.line > titleEnd && it.line < boldLine }
        val scope = Scope(labelled, page.bookers.filter { it.line == end })
        fun match(label: String): LabelValue? = labelled.firstNotNullOfOrNull { labelValue(label, it, scope) }
        // A label without a (usable) value is "" (invalid), a missing label null.
        fun field(label: String): String? = match(label)?.let { found -> found.value?.takeIf(::isUsable) ?: "" }
        fun <T> checkedField(label: String, check: (String) -> T?): TicketPdfField<T> =
            match(label)?.let { checked(it, check) } ?: TicketPdfField.NotFound(NotFoundReason.MISSING)
        return Block(
            title = title,
            cinema = checkedField(CINEMA, TicketPdfRules::cinema),
            date = checkedField(DATE, TicketPdfRules::date),
            time = checkedField(TIME, TicketPdfRules::time),
            hall = checkedField(HALL, TicketPdfRules::hall),
            seats = TicketPdfRules.seats(field(ROW), field(SEAT)),
        )
    }

    /**
     * The title: the bold lines right below "Ticket" in its column, each the only
     * segment of its line in the block (labels and values are regular, so no label
     * check here) and none a booker's ([isBookerTitleLine]), and the index of the last
     * of them (the anchor's line if there is none).
     */
    private fun title(anchor: Placed, segments: List<Placed>): Pair<TicketPdfField<String>, Int> {
        val parts = ArrayList<String>()
        var above = anchor.segment
        var last = anchor.line
        for ((line, placed) in segments.groupBy { it.line }.toSortedMap()) {
            val first = placed.first().segment
            val isTitleLine = placed.size == 1 && first.isBold && sameColumn(first, anchor.segment) && !isBookerTitleLine(first.text) &&
                above.y - first.y <= TITLE_LINE_DISTANCE * max(above.size, first.size)
            if (!isTitleLine) break
            parts += first.text
            above = first
            last = line
        }
        val title = when {
            parts.isEmpty() -> TicketPdfField.NotFound(NotFoundReason.MISSING)
            parts.size > MAX_TITLE_LINES -> TicketPdfField.NotFound(NotFoundReason.INVALID)
            else -> checked(LabelValue(parts.joinToString(" ")), TicketPdfRules::title, isTitle = true)
        }
        return title to last
    }

    /** A label found; [value] is `null` if nothing (usable) follows it. */
    private class LabelValue(val value: String?)

    /** The value of [label] if [placed] starts with it, else `null`; a value on the next line is looked for in [scope]. */
    private fun labelValue(label: String, placed: Placed, scope: Scope): LabelValue? {
        val text = placed.text
        return when {
            text == label || text == "$label:" -> LabelValue(valueBelow(placed, scope))
            text.startsWith("$label: ") -> LabelValue(text.substring(label.length + 2))
            text.startsWith("$label ") -> LabelValue(text.substring(label.length + 1))
            else -> null
        }
    }

    /**
     * The first segment of [scope] below [label] in its column, close enough to belong
     * to it and not a label itself. It is no value if its own line or its label's line
     * has a booker segment of the scope, or if the next line below it or the next
     * segment below it in its column is a booker line, however far down (a name on its
     * own line before "Gebucht von").
     */
    private fun valueBelow(label: Placed, scope: Scope): String? {
        val segments = scope.segments
        val below = segments.firstOrNull { it.line > label.line && sameColumn(it.segment, label.segment) } ?: return null
        if (!isNextLine(label, below) || isLabelLine(below.text)) return null
        if (scope.bookers.any { it.line == below.line || it.line == label.line }) return null
        val nextLine = minOf(
            segments.firstOrNull { it.line > below.line }?.line ?: Int.MAX_VALUE,
            scope.bookers.firstOrNull { it.line > below.line }?.line ?: Int.MAX_VALUE,
        )
        if (scope.bookers.any { it.line == nextLine }) return null
        val nextInColumn = (segments.asSequence() + scope.bookers.asSequence())
            .filter { it.line > below.line && sameColumn(it.segment, below.segment) }
            .minByOrNull { it.line }
        if (nextInColumn != null && isBookerLine(nextInColumn.text)) return null
        return below.text
    }

    /** [below] is on a line under [above], at most [NEXT_LINE_DISTANCE] font sizes away. */
    private fun isNextLine(above: Placed, below: Placed): Boolean {
        val distance = above.segment.y - below.segment.y
        return distance > 0 && distance <= NEXT_LINE_DISTANCE * max(above.segment.size, below.segment.size)
    }

    private fun isLabelLine(text: String): Boolean =
        isBookerLine(text) || isCategory(text) || LABELS.any { text == it || text.startsWith("$it ") || text.startsWith("$it:") }

    /** "Gebucht von …" in any case, with or without spaces (the cleaning has removed format characters). */
    private fun isBookerLine(text: String): Boolean = BOOKED in letters(text)

    /** A bold line below "Ticket" that is the booker's, not the title: it starts with "gebucht" or says "gebucht von". */
    private fun isBookerTitleLine(text: String): Boolean {
        val letters = letters(text)
        return letters.startsWith(BOOKED) || BOOKED_BY in letters
    }

    /** "Kat. …", "Kat.1 …", "KAT …" or "Kategorie …": the category, never a value. */
    private fun isCategory(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.startsWith("kategorie") || lower.startsWith("kat") && (lower.length == 3 || !lower[3].isLetter())
    }

    private fun letters(text: String): String = buildString(text.length) {
        for (char in text) if (char.isLetter()) append(char.lowercaseChar())
    }

    private fun sameColumn(a: TextSegment, b: TextSegment): Boolean = abs(a.x - b.x) <= tolerance(b)

    private fun tolerance(segment: TextSegment): Double = max(MIN_COLUMN_TOLERANCE, COLUMN_TOLERANCE * segment.size)

    /** No value mentions "gebucht" or starts like a category. */
    private fun isUsable(value: String): Boolean = !isBookerLine(value) && !isCategory(value)

    /**
     * [value] checked; one that mentions "gebucht" or starts like a category is never
     * used, a title only if it is a booker's line ([isBookerTitleLine]).
     */
    private fun <T> checked(value: LabelValue, check: (String) -> T?, isTitle: Boolean = false): TicketPdfField<T> {
        val raw = value.value ?: return TicketPdfField.NotFound(NotFoundReason.INVALID)
        if (if (isTitle) isBookerTitleLine(raw) else !isUsable(raw)) return TicketPdfField.NotFound(NotFoundReason.INVALID)
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
