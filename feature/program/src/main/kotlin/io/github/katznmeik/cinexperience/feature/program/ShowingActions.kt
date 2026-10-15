package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramLinks
import io.github.katznmeik.cinexperience.core.program.visitPrefill
import java.time.Instant
import java.time.LocalDate

/**
 * "Log visit" from a showing (spec §11.1): what Add visit is pre-filled with. The
 * app wires it to the visit form, which selects the hall of a one-hall cinema.
 *
 * @property cinemaId the app cinema of the showing, `null` if the app doesn't know it.
 */
data class LogVisitRequest(
    val cinemaId: Long?,
    val date: LocalDate,
    val filmTitle: String?,
    val format: ScreeningFormat?,
)

/** The actions of a showing row, shared by the Program tab and the film page. */
internal object ShowingActions {

    /** What "Book on yorck.de" does. */
    sealed interface Booking {
        data class Link(val url: String) : Booking

        /** The showing has started meanwhile (only upcoming showings are booked). */
        data object Started : Booking

        /** No link (an unusable ID and no film page), or the showing is gone. */
        data object None : Booking
    }

    /** "Book on yorck.de" for [showingId] at [now]: the link [ProgramLinks] builds, if it is still upcoming. */
    fun booking(program: Program?, showingId: String, now: Instant): Booking {
        val showing = program?.showings?.firstOrNull { it.id == showingId } ?: return Booking.None
        if (!showing.isUpcoming(now)) return Booking.Started
        return ProgramLinks.booking(showing, ProgramLookup.filmOf(program, showing))?.let { Booking.Link(it) } ?: Booking.None
    }

    /** The state change for [booking]: a link to open, or a message. */
    fun messageFor(booking: Booking): String? = when (booking) {
        is Booking.Link -> null
        Booking.Started -> ALREADY_STARTED
        Booking.None -> NO_LINK
    }

    /** The pre-fill of "Log visit" for [showingId], `null` if the showing is gone. */
    fun logVisit(program: Program?, showingId: String, join: CinemaJoin): LogVisitRequest? {
        val showing = program?.showings?.firstOrNull { it.id == showingId } ?: return null
        val prefill = showing.visitPrefill(ProgramLookup.filmOf(program, showing))
        return LogVisitRequest(
            cinemaId = join[prefill.cinemaSeedKey]?.id,
            date = prefill.date,
            filmTitle = prefill.filmTitle,
            format = prefill.screeningFormat,
        )
    }

    const val NO_LINK = "There is no yorck.de page for this showing."
    const val ALREADY_STARTED = "This showing has already started."
    const val NO_BROWSER = "No browser found to open yorck.de."
    const val SHOWING_GONE = "This showing is no longer in the program."
}
