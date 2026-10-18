package io.github.katznmeik.cinexperience.core.data.repository

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.model.DataLimits
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * Repository API for the feature modules. All flows emit domain models from
 * core:model; scores and seat colours are computed with core:model functions.
 * Names are trimmed and must not be blank (IllegalArgumentException otherwise);
 * blank optional texts are stored as null.
 */

/** Outcome of a delete request. */
enum class DeleteResult {
    DELETED,
    NOT_FOUND,

    /** Preloaded (seed) cinemas and halls cannot be deleted, only renamed/edited. */
    PRELOADED,
}

/**
 * [HallRepository.createHall] refused a new hall because [DataLimits.MAX_HALLS]
 * halls exist already. [message] is user-facing.
 */
class HallLimitReachedException : IllegalStateException(MESSAGE) {
    companion object {
        val MESSAGE: String =
            "The app holds at most ${DataLimits.MAX_HALLS_TEXT} halls. Delete a hall to add a new one."
    }
}

interface CinemaRepository {
    /** All cinemas with overall score and visit count, sorted by name (spec §6.1). */
    fun observeCinemas(): Flow<List<CinemaSummary>>

    /**
     * Every cinema with its halls, sorted by name (cinemas) and natural name (halls),
     * for "Manage cinemas & halls" and the data counts in Settings (spec §6.7).
     */
    fun observeCinemaDetails(): Flow<List<CinemaDetail>>

    /** Cinema header scores and its halls sorted by name (spec §6.2); `null` if it doesn't exist. */
    fun observeCinemaDetail(cinemaId: Long): Flow<CinemaDetail?>

    /**
     * Creates a user-added cinema and returns its id. Like every writer of these
     * repositories, it trims texts (blank → `null`) and refuses texts beyond
     * [io.github.katznmeik.cinexperience.core.model.TextLimits] with an
     * IllegalArgumentException, so whatever is saved can be exported.
     */
    suspend fun createCinema(name: String, address: String? = null, notes: String? = null): Long

    /** Updates name, address and notes; returns false if the cinema doesn't exist. */
    suspend fun updateCinema(cinemaId: Long, name: String, address: String?, notes: String?): Boolean

    suspend fun renameCinema(cinemaId: Long, name: String): Boolean

    /**
     * Marks the cinema as a favourite or unmarks it (spec §6.1, §6.2); any number
     * of cinemas can be favourites. Returns false if the cinema doesn't exist.
     * Every flow of this repository then emits the cinema with the new
     * [io.github.katznmeik.cinexperience.core.model.Cinema.isFavourite].
     */
    suspend fun setFavourite(cinemaId: Long, favourite: Boolean): Boolean

    /** Deletes a user-added cinema with its halls, visits and overrides. */
    suspend fun deleteCinema(cinemaId: Long): DeleteResult
}

interface HallRepository {
    /** Hall detail with layout, scores, visits (newest first) and seat states (spec §6.3). */
    fun observeHallDetail(hallId: Long): Flow<HallDetail?>

    suspend fun getHall(hallId: Long): Hall?

    /**
     * Creates a user-added hall in [cinemaId] and returns its id. Throws
     * [HallLimitReachedException] when [DataLimits.MAX_HALLS] halls exist already,
     * so whatever is saved can be exported.
     */
    suspend fun createHall(cinemaId: Long, name: String): Long

    suspend fun renameHall(hallId: Long, name: String): Boolean

    /**
     * Saves the editable fields of [hall]: name, layout, screen width, distance
     * and offset, reference image and notes. `cinemaId`, `isPreloaded`, `seedKey`
     * and `nextSeatId` are not changeable. [Hall.screenOffset] must be
     * [io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules.isValid]
     * (IllegalArgumentException otherwise; snap it with `ScreenOffsetRules.snap`).
     * A set screen width and distance must be valid for
     * [io.github.katznmeik.cinexperience.core.model.ScreenSizeRules], the name,
     * notes and row labels must fit their
     * [io.github.katznmeik.cinexperience.core.model.TextLimits], and the layout's
     * JSON must be at most `LayoutJson.MAX_JSON_LENGTH` chars (no layout within
     * `SeatLayout`'s limits comes near it); IllegalArgumentException otherwise.
     * Name, notes, row labels and screen size are checked against the limits only
     * when they change (an older build may have stored a value beyond today's
     * limits; it is kept as it is); a blank name, the layout JSON length, the
     * screen offset and the reference path are always checked. A row label counts
     * as unchanged if any row of the stored layout has it, so through this API an
     * existing over-long label could be moved or copied to other rows; the editor
     * can't do that, and the layout's size stays bounded.
     * A caller that doesn't deal with the offset keeps it by saving a copy of
     * the hall it read. When the layout changes, overrides of seats
     * that no longer exist are deleted (visits keep their seat id; it simply has no
     * label any more). Returns false if the hall doesn't exist.
     *
     * Seat ids are never reused within a hall: every seat that is not in the stored
     * layout must have an id ≥ the hall's [Hall.nextSeatId], otherwise this throws
     * IllegalArgumentException. Build layouts with
     * [io.github.katznmeik.cinexperience.core.model.LayoutEditSession]
     * (`hall.startLayoutEdit()`), which guarantees this, and show
     * `session.removedSeatsWithData(...)` first to warn the user (spec §6.5).
     *
     * A hall read with [Hall.layoutUnreadable] and saved with `layout = null` keeps
     * its stored (unreadable) layout; saving a new layout replaces it.
     */
    suspend fun updateHall(hall: Hall): Boolean

    /** Deletes a user-added hall with its visits and overrides. */
    suspend fun deleteHall(hallId: Long): DeleteResult
}

/** Outcome of [VisitRepository.saveVisit]. */
data class SaveVisitResult(
    val visitId: Long,
    /** The visit's seat was not in the hall's current layout and was removed. */
    val seatCleared: Boolean,
)

interface VisitRepository {
    /** Every visit across all cinemas, newest first (spec §6.8). */
    fun observeAllVisits(): Flow<List<VisitListItem>>

    fun observeVisit(visitId: Long): Flow<Visit?>

    suspend fun getVisit(visitId: Long): Visit?

    /**
     * Inserts ([Visit.id] == 0) or updates a visit. The seat must be a seat of the
     * hall's current layout; otherwise (e.g. the visit was moved to another hall,
     * or the hall has no layout) the seat is cleared and [SaveVisitResult.seatCleared]
     * is true so the UI can tell the user. An unchanged seat of an unchanged hall is
     * kept even if it was removed from the layout since. The visit's ticket (if
     * any) stays as it is; [TicketRepository.saveVisitWithTicket] saves both.
     */
    suspend fun saveVisit(visit: Visit): SaveVisitResult

    /** Deletes the visit with its ticket; false if it doesn't exist. */
    suspend fun deleteVisit(visitId: Long): Boolean
}

/** Outcome of [TicketRepository.saveVisitWithTicket]. */
sealed interface SaveTicketResult {
    /** The visit and its ticket were saved; [seatCleared] as in [SaveVisitResult]. */
    data class Saved(val visitId: Long, val seatCleared: Boolean) : SaveTicketResult

    /**
     * Nothing was saved: the booking number belongs to the ticket of another visit,
     * [visitId] (spec §6.10 "Imported before": open that visit instead).
     */
    data class BookingNumberInUse(val visitId: Long) : SaveTicketResult
}

/**
 * Tickets (spec §4.7): at most one per visit, deleted with the visit, booking
 * numbers unique across tickets. Every value is checked by the model types
 * ([Ticket], [BookingNumber], [io.github.katznmeik.cinexperience.core.model.TicketSeat],
 * [QrModules]), so whatever is saved can be exported. Backups carry tickets
 * without their QR modules (spec §8).
 */
interface TicketRepository {
    /** The visit's ticket; `null` if it has none (or the visit doesn't exist). */
    fun observeTicket(visitId: Long): Flow<Ticket?>

    suspend fun getTicket(visitId: Long): Ticket?

    /** The visit whose ticket has [bookingNumber], `null` if none has (spec §6.10 "Imported before"). */
    suspend fun findVisitId(bookingNumber: BookingNumber): Long?

    /**
     * Saves [visit] with the same rules as [VisitRepository.saveVisit] (insert for
     * [Visit.id] == 0, otherwise update; the seat check) and, in the same
     * transaction, [ticket] as its ticket, replacing a ticket it had (also its QR
     * modules: a ticket without them removes them). [Ticket.visitId] must be
     * [Visit.id] (`0` for a new visit; IllegalArgumentException otherwise); the
     * stored ticket gets the visit's id.
     *
     * A booking number that already belongs to the ticket of **another** visit is
     * refused: nothing is saved, and [SaveTicketResult.BookingNumberInUse] names
     * that visit. Saving a visit again with its own booking number is fine.
     */
    suspend fun saveVisitWithTicket(visit: Visit, ticket: Ticket): SaveTicketResult

    /** Removes the visit's ticket (spec §6.4 "Remove ticket"); false if it had none. */
    suspend fun removeTicket(visitId: Long): Boolean

    /**
     * Stores [qrModules] for the visit's ticket again when the ticket is imported
     * again after they were removed (spec §6.10), in one guarded write: only if that
     * ticket still has [bookingNumber] and no QR modules, so a ticket that changed
     * since it was read (removed, replaced, or given a QR code meanwhile) is left
     * alone. False if nothing was stored. Modules that are already expired are
     * stored too and go with the next [removeExpiredQrModules].
     */
    suspend fun restoreQrModules(visitId: Long, bookingNumber: BookingNumber, qrModules: QrModules): Boolean

    /**
     * Removes the QR modules of every ticket whose showing ended long enough ago
     * ([io.github.katznmeik.cinexperience.core.model.ShowingTime.qrModulesExpired]
     * at [now]: 7 days after the start, or after the end of the visit's date
     * without a start time) and returns how many were removed. The tickets stay.
     */
    suspend fun removeExpiredQrModules(now: Instant): Int
}

/**
 * Manual seat colours and the favourite seat (spec §4.6). The seat must exist in
 * the hall's current layout (IllegalArgumentException otherwise).
 */
interface SeatOverrideRepository {
    /** Sets the manual colour, or clears it with `null` (back to automatic). */
    suspend fun setSeatColour(hallId: Long, seatId: SeatId, colour: SeatColour?)

    /** Makes [seatId] the hall's only favourite; `null` clears the favourite. */
    suspend fun setFavouriteSeat(hallId: Long, seatId: SeatId?)

    /** Removes colour and favourite of one seat. */
    suspend fun clearSeatOverride(hallId: Long, seatId: SeatId)
}
