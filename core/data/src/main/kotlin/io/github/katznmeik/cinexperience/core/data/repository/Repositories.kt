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

    /** Cinema header scores and its halls sorted by name (spec §6.2); `null` if it doesn't exist. */
    fun observeCinemaDetail(cinemaId: Long): Flow<CinemaDetail?>

    /** Creates a user-added cinema and returns its id. */
    suspend fun createCinema(name: String, address: String? = null, notes: String? = null): Long

    /** Updates name, address and notes; returns false if the cinema doesn't exist. */
    suspend fun updateCinema(cinemaId: Long, name: String, address: String?, notes: String?): Boolean

    suspend fun renameCinema(cinemaId: Long, name: String): Boolean

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
     * Saves the editable fields of [hall]: name, layout, screen width/distance,
     * reference image and notes. `cinemaId`, `isPreloaded` and `seedKey` are not
     * changeable. When the layout changes, overrides of seats that no longer exist
     * are deleted (visits keep their seat id; it simply has no label any more).
     * Check [io.github.katznmeik.cinexperience.core.model.removedSeatsWithData]
     * first to warn the user (spec §6.5). Returns false if the hall doesn't exist.
     */
    suspend fun updateHall(hall: Hall): Boolean

    /** Deletes a user-added hall with its visits and overrides. */
    suspend fun deleteHall(hallId: Long): DeleteResult
}

interface VisitRepository {
    /** Every visit across all cinemas, newest first (spec §6.8). */
    fun observeAllVisits(): Flow<List<VisitListItem>>

    fun observeVisit(visitId: Long): Flow<Visit?>

    suspend fun getVisit(visitId: Long): Visit?

    /** Inserts ([Visit.id] == 0) or updates a visit and returns its id. */
    suspend fun saveVisit(visit: Visit): Long

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
