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
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.model.cinemaScores
import io.github.katznmeik.cinexperience.core.model.hallScores
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Everything the fake repositories hold. Favourite seats are given by label here,
 * because the fake has no seat overrides table.
 */
data class FakeStore(
    val cinemas: List<Cinema> = emptyList(),
    val halls: List<Hall> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val favourites: Map<Long, SeatLabel> = emptyMap(),
)

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
            HallSummary(hall, hallScores.overall, hallScores.visitCount, favourites[hall.id])
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
            )
        }
        return DeleteResult.DELETED
    }

    // HallRepository

    override fun observeHallDetail(hallId: Long): Flow<HallDetail?> = data.map { s ->
        val hall = s.halls.firstOrNull { it.id == hallId } ?: return@map null
        val cinema = s.cinemas.first { it.id == hall.cinemaId }
        val visits = s.visits.filter { it.hallId == hallId }.sortedByDescending { it.date }
        HallDetail(hall, cinema, hallScores(visits), visits, hall.layout?.labels(), emptyMap(), emptyList())
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

    override suspend fun updateHall(hall: Hall): Boolean {
        write()
        val cleanName = hall.name.requireName()
        if (store.value.halls.none { it.id == hall.id }) return false
        store.update { s -> s.copy(halls = s.halls.map { if (it.id == hall.id) hall.copy(name = cleanName) else it }) }
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
            )
        }
        return DeleteResult.DELETED
    }

    // VisitRepository

    override fun observeAllVisits(): Flow<List<VisitListItem>> = data.map { s ->
        s.visits.sortedByDescending { it.date }.map { visit ->
            val hall = s.halls.first { it.id == visit.hallId }
            val cinema = s.cinemas.first { it.id == hall.cinemaId }
            VisitListItem(visit, cinema.id, cinema.name, hall.name, visit.seatId?.let { hall.layout?.labels()?.labelOf(it) })
        }
    }.distinctUntilChanged()

    override fun observeVisit(visitId: Long): Flow<Visit?> =
        data.map { s -> s.visits.firstOrNull { it.id == visitId } }.distinctUntilChanged()

    override suspend fun getVisit(visitId: Long): Visit? = store.value.visits.firstOrNull { it.id == visitId }

    override suspend fun saveVisit(visit: Visit): SaveVisitResult {
        write()
        val saved = if (visit.id == 0L) visit.copy(id = nextId++) else visit
        store.update { s -> s.copy(visits = s.visits.filter { it.id != saved.id } + saved) }
        return SaveVisitResult(saved.id, seatCleared = false)
    }

    override suspend fun deleteVisit(visitId: Long): Boolean {
        write()
        val exists = store.value.visits.any { it.id == visitId }
        store.update { s -> s.copy(visits = s.visits.filter { it.id != visitId }) }
        return exists
    }

    // SeatOverrideRepository (colours are not modelled by the fake)

    override suspend fun setSeatColour(hallId: Long, seatId: SeatId, colour: SeatColour?) = write()

    override suspend fun setFavouriteSeat(hallId: Long, seatId: SeatId?) {
        write()
        val label = seatId?.let { id -> getHall(hallId)?.layout?.labels()?.labelOf(id) }
        store.update { s -> s.copy(favourites = if (label == null) s.favourites - hallId else s.favourites + (hallId to label)) }
    }

    override suspend fun clearSeatOverride(hallId: Long, seatId: SeatId) = write()

    private fun String.requireName(): String = trim().also { require(it.isNotEmpty()) { "Name must not be blank" } }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
