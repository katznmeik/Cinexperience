package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramLinks
import io.github.katznmeik.cinexperience.core.program.visitPrefill
import java.time.Instant

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

    /**
     * The pre-fill of "Log visit" for [showingId] (spec §11.1), `null` if the showing
     * is gone. Its cinema is `null` if the app doesn't know the showing's cinema.
     */
    fun logVisit(program: Program?, showingId: String, join: CinemaJoin): VisitPrefillArgs? {
        val showing = program?.showings?.firstOrNull { it.id == showingId } ?: return null
        val prefill = showing.visitPrefill(ProgramLookup.filmOf(program, showing))
        return VisitPrefillArgs(
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
