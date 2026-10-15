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

    /**
     * "Book on yorck.de": the link [ProgramLinks] builds for [showingId], `null` if
     * there is none or the showing has started at [now] (only upcoming showings are booked).
     */
    fun bookingLink(program: Program?, showingId: String, now: Instant): String? {
        val showing = program?.showings?.firstOrNull { it.id == showingId }?.takeIf { it.isUpcoming(now) } ?: return null
        return ProgramLinks.booking(showing, ProgramLookup.filmOf(program, showing))
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
    const val NO_BROWSER = "No browser found to open yorck.de."
    const val SHOWING_GONE = "This showing is no longer in the program."
}
