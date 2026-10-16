package io.github.katznmeik.cinexperience.core.data.repository

import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import kotlinx.coroutines.flow.Flow

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

    /** Creates a user-added hall in [cinemaId] and returns its id. */
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
     * kept even if it was removed from the layout since.
     */
    suspend fun saveVisit(visit: Visit): SaveVisitResult

    suspend fun deleteVisit(visitId: Long): Boolean
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
