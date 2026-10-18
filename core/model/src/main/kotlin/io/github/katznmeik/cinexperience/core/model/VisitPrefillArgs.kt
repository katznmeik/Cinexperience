package io.github.katznmeik.cinexperience.core.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * What a new visit is pre-filled with: "Log visit" from a showing (spec §11.1),
 * built by feature:program, or an imported ticket (§6.10), built by
 * feature:tickets; feature:visits opens Add visit with it. The form selects the
 * cinema's hall if it has only one; otherwise the cinema is set and the owner picks
 * the hall. `null` fields stay as for a blank visit.
 *
 * @property startTime the showing's start (Berlin clock time, to the minute).
 * @property seatId the owner's seat; used only when Add visit is opened for a hall
 *   (a seat id belongs to one hall).
 * @property ticket the ticket read from the PDF, stored with the visit on Save.
 * @property notes what couldn't be read from the ticket or matched in the app, as
 *   printed, for manual entry (e.g. "Hall on the ticket: Saal 9 (not in the app)");
 *   plain text, shown in the form and never stored.
 */
data class VisitPrefillArgs(
    val cinemaId: Long? = null,
    val date: LocalDate? = null,
    val filmTitle: String? = null,
    val format: ScreeningFormat? = null,
    val startTime: LocalTime? = null,
    val seatId: SeatId? = null,
    val ticket: TicketDraft? = null,
    val notes: List<String> = emptyList(),
) {
    companion object {
        /** At most this many [notes] are kept (the import writes fewer). */
        const val MAX_NOTES: Int = 10

        /** A longer note is cut to this many characters. */
        const val MAX_NOTE_LENGTH: Int = 400
    }
}
