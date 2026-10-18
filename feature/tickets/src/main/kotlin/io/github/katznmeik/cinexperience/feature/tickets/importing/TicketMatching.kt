package io.github.katznmeik.cinexperience.feature.tickets.importing

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.model.seatOf
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.YorckCinemas
import io.github.katznmeik.cinexperience.core.program.screeningFormat
import io.github.katznmeik.cinexperience.core.ticket.NotFoundReason
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfContent
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfField
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import java.time.LocalDateTime
import java.util.Locale

/**
 * What the import opens Add visit with (spec §6.10): [hallId] when the hall was
 * matched (Add visit is then opened for it), and the [prefill] with the cinema,
 * date, start time, film, format, the owner's seat, the ticket draft and the notes.
 */
data class TicketMatch(val hallId: Long?, val prefill: VisitPrefillArgs)

/**
 * Matches a read ticket with the app's data (spec §11.2, Matching). Pure: it reads
 * only what it is given, the program included (the one already on the phone; the
 * import never downloads).
 *
 * - **Cinema:** the printed name through Yorck's name table ([YorckCinemas]), as
 *   printed and with "Kino " in front; then the app's cinema with that seed key (a
 *   preloaded one, or a user cinema the seeder adopted). A cinema the user deleted
 *   isn't in [cinemas], so it isn't matched.
 * - **Hall:** within that cinema, the hall named "Saal " + the printed hall or named
 *   as printed (ignoring case, runs of spaces as one). A one-hall cinema needs the
 *   printed hall to match too; otherwise only the cinema is set and Add visit selects
 *   its only hall, as for a cinema chosen by hand.
 * - **Seats:** every printed seat against the hall's [labels] ([seatOf]); the first
 *   one is the owner's seat. Unmatched seats stay on the ticket and select nothing.
 * - **Format:** a showing of the program at the cinema with the same Berlin start
 *   ([screeningFormat]).
 * - **Notes:** everything that couldn't be read or matched, with what was printed.
 */
object TicketMatching {

    /** [qrModules] the ticket's QR code ([TicketReadResult.Read.qrModules]). */
    fun match(
        content: TicketPdfContent,
        bookingNumber: BookingNumber,
        qrModules: QrModules?,
        cinemas: List<CinemaDetail>,
        program: Program?,
    ): TicketMatch {
        val notes = ArrayList<String>()
        notes += fieldNotes(content)

        val printedCinema = content.cinema.valueOrNull
        val cinema = printedCinema?.let { cinemaFor(it, cinemas) }
        if (printedCinema != null && cinema == null) notes += TicketNotes.cinemaNotInApp(printedCinema)

        val printedHall = content.hall.valueOrNull
        val hall = if (cinema != null && printedHall != null) hallFor(printedHall, cinema) else null
        if (printedHall != null && hall == null) notes += TicketNotes.hall(printedHall, cinemaInApp = cinema != null)

        val seats = content.seats.valueOrNull.orEmpty().mapNotNull { TicketSeat.ofOrNull(it.row, it.seat) }.distinct().take(Ticket.MAX_SEATS)
        val labels = hall?.layout?.labels()
        val seatIds = seats.map { seat -> labels?.seatOf(seat) }
        if (hall != null) {
            val unmatched = seats.filterIndexed { index, _ -> seatIds[index] == null }
            if (unmatched.isNotEmpty()) notes += TicketNotes.seatsNotInLayout(unmatched, hasLayout = hall.layout != null)
        }

        val date = content.date.valueOrNull
        val startTime = content.startTime.valueOrNull
        val filmTitle = content.filmTitle.valueOrNull
        val seedKey = cinema?.cinema?.seedKey
        val format = if (seedKey != null && date != null && startTime != null) {
            screeningFormat(program, seedKey, date.atTime(startTime), filmTitle)
        } else {
            null
        }
        val prefill = VisitPrefillArgs(
            cinemaId = cinema?.cinema?.id,
            date = date,
            filmTitle = filmTitle,
            format = format,
            startTime = startTime,
            seatId = seatIds.firstOrNull(),
            ticket = TicketDraft(bookingNumber, seats, qrModules),
            notes = notes.take(VisitPrefillArgs.MAX_NOTES),
        )
        return TicketMatch(hall?.id, prefill)
    }

    /** The app's cinema Yorck prints as [printed] (with or without "Kino " in front); `null` if the app has none. */
    fun cinemaFor(printed: String, cinemas: List<CinemaDetail>): CinemaDetail? {
        val seedKey = YorckCinemas.seedKeyFor(printed) ?: YorckCinemas.seedKeyFor("Kino $printed") ?: return null
        return cinemas.singleOrNull { it.cinema.seedKey == seedKey }
    }

    /** The hall of [cinema] printed as [printed]: "Saal 4" for "4", or a hall named as printed; `null` if none or several. */
    fun hallFor(printed: String, cinema: CinemaDetail): Hall? {
        val names = setOf(normalised("Saal $printed"), normalised(printed))
        return cinema.halls.map { it.hall }.singleOrNull { normalised(it.name) in names }
    }

    /**
     * The screening format of the showing at the app cinema [seedKey] starting at
     * [start] (Berlin clock time), from its versions (§11.1: DF, OmU, OV). With
     * several showings at that time (different halls), those whose film has the
     * ticket's [filmTitle] count, if any do. `null` if none is found, or they don't
     * agree on one format.
     */
    fun screeningFormat(program: Program?, seedKey: String, start: LocalDateTime, filmTitle: String?): ScreeningFormat? {
        program ?: return null
        val showings = program.showings.filter { it.cinema.seedKey == seedKey && it.start == start }
        if (showings.isEmpty()) return null
        val title = filmTitle?.let(::normalised)
        val sameFilm = if (showings.size > 1 && title != null) {
            showings.filter { normalised(program.filmOf(it).title) == title }
        } else {
            emptyList()
        }
        return sameFilm.ifEmpty { showings }.map { it.screeningFormat() }.distinct().singleOrNull()
    }

    /** "Not on the ticket: …" and "Couldn't be read from the ticket: …", in the form's order. */
    private fun fieldNotes(content: TicketPdfContent): List<String> {
        val fields = listOf<Pair<String, TicketPdfField<*>>>(
            "cinema" to content.cinema,
            "hall" to content.hall,
            "date" to content.date,
            "start time" to content.startTime,
            "film title" to content.filmTitle,
            "seats" to content.seats,
        )
        val missing = fields.filter { (_, field) -> (field as? TicketPdfField.NotFound)?.reason == NotFoundReason.MISSING }
        val unreadable = fields.filter { (_, field) -> field is TicketPdfField.NotFound && field.reason != NotFoundReason.MISSING }
        return listOfNotNull(
            missing.takeIf { it.isNotEmpty() }?.let { TicketNotes.missing(it.map { (name, _) -> name }) },
            unreadable.takeIf { it.isNotEmpty() }?.let { TicketNotes.unreadable(it.map { (name, _) -> name }) },
        )
    }

    private fun normalised(name: String): String = name.trim().split(WHITESPACE).joinToString(" ").lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
}

/** The notes of an import (spec §6.10): what to fill in by hand, with what was printed. Plain text. */
object TicketNotes {
    fun missing(fields: List<String>): String = "Not on the ticket: ${fields.joinToString(", ")}"

    fun unreadable(fields: List<String>): String = "Couldn't be read from the ticket: ${fields.joinToString(", ")}"

    fun cinemaNotInApp(printed: String): String = "Cinema on the ticket: ${isolate(printed)} (not in the app)"

    /** The printed hall; with [cinemaInApp] the cinema was matched but has no such hall. */
    fun hall(printed: String, cinemaInApp: Boolean): String {
        val text = "Hall on the ticket: ${isolate("Saal $printed")}"
        return if (cinemaInApp) "$text (not in the app)" else text
    }

    fun seatsNotInLayout(seats: List<TicketSeat>, hasLayout: Boolean): String {
        val what = if (seats.size == 1) "Seat on the ticket" else "Seats on the ticket"
        val why = if (hasLayout) "not in the hall's layout" else "the hall has no seat layout yet"
        return "$what: ${seats.joinToString(", ") { isolate(it.label.shortText) }} ($why)"
    }
}
