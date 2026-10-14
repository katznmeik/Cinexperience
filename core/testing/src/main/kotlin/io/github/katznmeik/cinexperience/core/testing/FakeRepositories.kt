package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.NameOrder
import io.github.katznmeik.cinexperience.core.data.repository.SaveVisitResult
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.SeatOverrides
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.model.cinemaScores
import io.github.katznmeik.cinexperience.core.model.hallScores
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.model.seatStates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Newest first: by date, then by insertion order (id), like the Room repositories. */
private val NEWEST_FIRST = compareByDescending<Visit> { it.date }.thenByDescending { it.id }

/**
 * Everything the fake repositories hold.
 *
 * @property favourites fixture shortcut: favourite seats by label (the first seat
 *   with that label). A favourite in [overrides] takes precedence for its hall.
 * @property overrides manual colours and favourites by seat id, as written by the
 *   [SeatOverrideRepository] methods.
 */
data class FakeStore(
    val cinemas: List<Cinema> = emptyList(),
    val halls: List<Hall> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val favourites: Map<Long, SeatLabel> = emptyMap(),
    val overrides: List<SeatOverride> = emptyList(),
) {
    /** The hall's overrides, with the [favourites] label shortcut resolved to a seat id. */
    fun overridesOf(hallId: Long): List<SeatOverride> {
        val own = overrides.filter { it.hallId == hallId }
        if (own.any { it.isFavourite }) return own
        val labels = halls.firstOrNull { it.id == hallId }?.layout?.labels() ?: return own
        val favourite = favourites[hallId]?.let { label -> labels.seats.values.firstOrNull { it.label == label }?.id }
            ?: return own
        return SeatOverrides.withFavourite(own, hallId, favourite)
    }
}

/**
 * In-memory implementation of the core:data repositories for ViewModel tests and
 * screenshot tests. Scores come from the core:model functions, like in the real
 * repositories; lists are sorted with the same natural [NameOrder] as Room.
 *
 * Set [failure] to make every write throw it, and [observeFailure] to make every
 * flow fail (to test error handling).
 */
class FakeCinexRepository(initial: FakeStore = FakeStore()) :
    CinemaRepository,
    HallRepository,
    VisitRepository,
    SeatOverrideRepository {

    val store = MutableStateFlow(initial)

    /** When set, every write throws this exception. */
    var failure: Exception? = null

    /** When set, every flow collected afterwards throws this exception. */
    var observeFailure: Exception? = null

    private var nextId = 100_000L

    /** The store as a flow, failing with [observeFailure] if set. */
    private val data: Flow<FakeStore> = flow {
        observeFailure?.let { throw it }
        emitAll(store)
    }

    private fun write() {
        failure?.let { throw it }
    }

    private fun FakeStore.detailOf(cinema: Cinema): CinemaDetail {
        val cinemaHalls = halls.filter { it.cinemaId == cinema.id }
        val visitsByHall = cinemaHalls.associate { hall -> hall.id to visits.filter { it.hallId == hall.id } }
        val scores = cinemaScores(visitsByHall)
        val hallSummaries = cinemaHalls.map { hall ->
            val hallScores = scores.halls.getValue(hall.id)
            val favourite = SeatOverrides.favourite(overridesOf(hall.id))
            HallSummary(hall, hallScores.overall, hallScores.visitCount, favourite?.let { hall.layout?.labels()?.labelOf(it) })
        }.sortedWith(compareBy(NameOrder) { it.hall.name })
        return CinemaDetail(cinema, scores, hallSummaries)
    }

    private val FakeStore.sortedCinemas: List<Cinema> get() = cinemas.sortedWith(compareBy(NameOrder) { it.name })

    // CinemaRepository

    override fun observeCinemas(): Flow<List<CinemaSummary>> = data.map { s ->
        s.sortedCinemas.map { cinema ->
            val detail = s.detailOf(cinema)
            CinemaSummary(cinema, detail.scores.overall, detail.scores.visitCount, detail.halls.size)
        }
    }.distinctUntilChanged()

    override fun observeCinemaDetails(): Flow<List<CinemaDetail>> =
        data.map { s -> s.sortedCinemas.map { s.detailOf(it) } }.distinctUntilChanged()

    override fun observeCinemaDetail(cinemaId: Long): Flow<CinemaDetail?> =
        data.map { s -> s.cinemas.firstOrNull { it.id == cinemaId }?.let { s.detailOf(it) } }.distinctUntilChanged()

    override suspend fun createCinema(name: String, address: String?, notes: String?): Long {
        write()
        val id = nextId++
        store.update {
            it.copy(cinemas = it.cinemas + Cinema(id, name.requireName(), address.clean(), notes.clean()))
        }
        return id
    }

    override suspend fun updateCinema(cinemaId: Long, name: String, address: String?, notes: String?): Boolean {
        write()
        val cleanName = name.requireName()
        if (store.value.cinemas.none { it.id == cinemaId }) return false
        store.update { s ->
            s.copy(
                cinemas = s.cinemas.map {
                    if (it.id == cinemaId) it.copy(name = cleanName, address = address.clean(), notes = notes.clean()) else it
                },
            )
        }
        return true
    }

    override suspend fun renameCinema(cinemaId: Long, name: String): Boolean {
        val cinema = store.value.cinemas.firstOrNull { it.id == cinemaId } ?: return false
        return updateCinema(cinemaId, name, cinema.address, cinema.notes)
    }

    /** Like Room: false for an unknown cinema; any number of cinemas can be favourites. */
    override suspend fun setFavourite(cinemaId: Long, favourite: Boolean): Boolean {
        write()
        if (store.value.cinemas.none { it.id == cinemaId }) return false
        store.update { s ->
            s.copy(cinemas = s.cinemas.map { if (it.id == cinemaId) it.copy(isFavourite = favourite) else it })
        }
        return true
    }

    override suspend fun deleteCinema(cinemaId: Long): DeleteResult {
        write()
        val cinema = store.value.cinemas.firstOrNull { it.id == cinemaId } ?: return DeleteResult.NOT_FOUND
        if (cinema.isPreloaded) return DeleteResult.PRELOADED
        store.update { s ->
            val hallIds = s.halls.filter { it.cinemaId == cinemaId }.map { it.id }.toSet()
            s.copy(
                cinemas = s.cinemas - cinema,
                halls = s.halls.filter { it.id !in hallIds },
                visits = s.visits.filter { it.hallId !in hallIds },
                favourites = s.favourites - hallIds,
                overrides = s.overrides.filter { it.hallId !in hallIds },
            )
        }
        return DeleteResult.DELETED
    }

    // HallRepository

    override fun observeHallDetail(hallId: Long): Flow<HallDetail?> = data.map { s ->
        val hall = s.halls.firstOrNull { it.id == hallId } ?: return@map null
        val cinema = s.cinemas.first { it.id == hall.cinemaId }
        val visits = s.visits.filter { it.hallId == hallId }.sortedWith(NEWEST_FIRST)
        val labels = hall.layout?.labels()
        val overrides = s.overridesOf(hallId).sortedBy { it.seatId.value }
        val seats = hall.layout?.let { seatStates(it, visits, overrides) }.orEmpty()
        HallDetail(hall, cinema, hallScores(visits), visits, labels, seats, overrides)
    }.distinctUntilChanged()

    override suspend fun getHall(hallId: Long): Hall? = store.value.halls.firstOrNull { it.id == hallId }

    override suspend fun createHall(cinemaId: Long, name: String): Long {
        write()
        val cleanName = name.requireName()
        require(store.value.cinemas.any { it.id == cinemaId }) { "No cinema $cinemaId" }
        val id = nextId++
        store.update { it.copy(halls = it.halls + Hall(id = id, cinemaId = cinemaId, name = cleanName)) }
        return id
    }

    override suspend fun renameHall(hallId: Long, name: String): Boolean {
        val hall = getHall(hallId) ?: return false
        return updateHall(hall.copy(name = name))
    }

    /**
     * Like the Room repository: only the editable fields change, seat ids must not
     * be reused (IllegalArgumentException) and the hall's id mark is raised.
     */
    override suspend fun updateHall(hall: Hall): Boolean {
        write()
        val cleanName = hall.name.requireName()
        require(ScreenOffsetRules.isValid(hall.screenOffset)) { "Invalid screen offset ${hall.screenOffset}" }
        val existing = store.value.halls.firstOrNull { it.id == hall.id } ?: return false
        require(existing.cinemaId == hall.cinemaId) { "Moving a hall to another cinema is not supported" }
        val stored = existing.layout
        val mark = maxOf(existing.nextSeatId, stored?.nextSeatId ?: SeatLayout.FIRST_SEAT_ID)
        val newLayout = hall.layout
        if (newLayout != null) {
            val storedIds = stored?.seatIds.orEmpty()
            val reused = newLayout.seatIds.any { it !in storedIds && it.value < mark }
            require(!reused && (stored == null || newLayout.isEditOf(stored))) {
                "Layout of hall ${hall.id} reuses seat ids of other seats (mark $mark)"
            }
        }
        val keepUnreadable = newLayout == null && hall.layoutUnreadable && existing.layoutUnreadable
        val updated = existing.copy(
            name = cleanName,
            layout = newLayout,
            screenWidth = hall.screenWidth,
            screenDistance = hall.screenDistance,
            screenOffset = hall.screenOffset + 0f,
            referenceImagePath = hall.referenceImagePath.clean(),
            notes = hall.notes.clean(),
            nextSeatId = maxOf(mark, newLayout?.nextSeatId ?: 0),
            layoutUnreadable = keepUnreadable,
        )
        // Like Room: only a changed layout touches the overrides. A kept unreadable
        // layout is unchanged; replacing an unreadable one, or clearing a layout, is a change.
        val layoutChanged = !keepUnreadable && (newLayout != stored || existing.layoutUnreadable)
        store.update { s ->
            if (!layoutChanged) {
                s.copy(halls = s.halls.map { if (it.id == hall.id) updated else it })
            } else {
                // Overrides of seats that no longer exist are deleted. The label shortcut
                // is resolved against the stored layout first, so the favourite cannot
                // jump to a new seat that happens to have the same label.
                val remaining = newLayout?.seatIds.orEmpty()
                val kept = s.overridesOf(hall.id).filter { it.seatId in remaining }
                s.copy(
                    halls = s.halls.map { if (it.id == hall.id) updated else it },
                    favourites = s.favourites - hall.id,
                    overrides = s.overrides.filter { it.hallId != hall.id } + kept,
                )
            }
        }
        return true
    }

    override suspend fun deleteHall(hallId: Long): DeleteResult {
        write()
        val hall = getHall(hallId) ?: return DeleteResult.NOT_FOUND
        if (hall.isPreloaded) return DeleteResult.PRELOADED
        store.update { s ->
            s.copy(
                halls = s.halls - hall,
                visits = s.visits.filter { it.hallId != hallId },
                favourites = s.favourites - hallId,
                overrides = s.overrides.filter { it.hallId != hallId },
            )
        }
        return DeleteResult.DELETED
    }

    // VisitRepository

    override fun observeAllVisits(): Flow<List<VisitListItem>> = data.map { s ->
        s.visits.sortedWith(NEWEST_FIRST).map { visit ->
            val hall = s.halls.first { it.id == visit.hallId }
            val cinema = s.cinemas.first { it.id == hall.cinemaId }
            VisitListItem(visit, cinema.id, cinema.name, hall.name, visit.seatId?.let { hall.layout?.labels()?.labelOf(it) })
        }
    }.distinctUntilChanged()

    override fun observeVisit(visitId: Long): Flow<Visit?> =
        data.map { s -> s.visits.firstOrNull { it.id == visitId } }.distinctUntilChanged()

    override suspend fun getVisit(visitId: Long): Visit? = store.value.visits.firstOrNull { it.id == visitId }

    /**
     * Like the Room repository: texts are trimmed (blank → null), and a seat that
     * is not in the hall's current layout is cleared unless it is the visit's
     * unchanged seat in its unchanged hall.
     */
    override suspend fun saveVisit(visit: Visit): SaveVisitResult {
        write()
        val s = store.value
        val stored = if (visit.id == 0L) null else requireNotNull(s.visits.firstOrNull { it.id == visit.id }) { "No visit ${visit.id}" }
        val hall = requireNotNull(s.halls.firstOrNull { it.id == visit.hallId }) { "No hall ${visit.hallId}" }
        val unchangedSeat = stored != null && stored.hallId == visit.hallId && stored.seatId == visit.seatId
        val seatValid = visit.seatId == null || unchangedSeat || hall.layout?.seatIds?.contains(visit.seatId) == true
        val saved = visit.copy(
            id = if (visit.id == 0L) nextId++ else visit.id,
            filmTitle = visit.filmTitle.clean(),
            note = visit.note.clean(),
            photoPath = visit.photoPath.clean(),
            seatId = if (seatValid) visit.seatId else null,
        )
        store.update { st -> st.copy(visits = st.visits.filter { it.id != saved.id } + saved) }
        return SaveVisitResult(saved.id, seatCleared = !seatValid)
    }

    override suspend fun deleteVisit(visitId: Long): Boolean {
        write()
        val exists = store.value.visits.any { it.id == visitId }
        store.update { s -> s.copy(visits = s.visits.filter { it.id != visitId }) }
        return exists
    }

    // SeatOverrideRepository: like Room, the seat must be in the hall's current layout.

    override suspend fun setSeatColour(hallId: Long, seatId: SeatId, colour: SeatColour?) =
        modifyOverrides(hallId, seatId) { SeatOverrides.withColour(it, hallId, seatId, colour) }

    override suspend fun setFavouriteSeat(hallId: Long, seatId: SeatId?) =
        modifyOverrides(hallId, seatId) { SeatOverrides.withFavourite(it, hallId, seatId) }

    override suspend fun clearSeatOverride(hallId: Long, seatId: SeatId) =
        modifyOverrides(hallId, seatId) { SeatOverrides.cleared(it, hallId, seatId) }

    private fun modifyOverrides(hallId: Long, seatId: SeatId?, change: (List<SeatOverride>) -> List<SeatOverride>) {
        write()
        val hall = requireNotNull(store.value.halls.firstOrNull { it.id == hallId }) { "No hall $hallId" }
        if (seatId != null) {
            require(hall.layout?.seatIds?.contains(seatId) == true) { "$seatId is not in the layout of hall $hallId" }
        }
        store.update { s ->
            // The label shortcut is resolved into real overrides on the first write.
            val updated = change(s.overridesOf(hallId))
            s.copy(favourites = s.favourites - hallId, overrides = s.overrides.filter { it.hallId != hallId } + updated)
        }
    }

    private fun String.requireName(): String = trim().also { require(it.isNotEmpty()) { "Name must not be blank" } }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
