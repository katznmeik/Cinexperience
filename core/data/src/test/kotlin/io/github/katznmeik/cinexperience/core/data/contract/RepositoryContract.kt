package io.github.katznmeik.cinexperience.core.data.contract

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The repository rules that the in-memory fake of core:testing
 * (`FakeCinexRepository`, which every ViewModel and screenshot test runs on)
 * re-implements, checked against the Room repositories and the fake alike
 * ([RoomRepositoryContractTest], [FakeRepositoryContractTest]), so the two can't
 * drift apart unnoticed: seat ids and the hall's id mark, the overrides a layout
 * edit prunes, one favourite seat per hall, a seat cleared on save, deletes and
 * favourite cinemas. Each scenario starts on empty data and uses only the
 * repository interfaces, except [insertUnchecked] for data the repositories
 * themselves refuse to write.
 */
internal abstract class RepositoryContract {

    /** The repositories under test, on empty data. */
    protected class Repositories(
        val cinemas: CinemaRepository,
        val halls: HallRepository,
        val visits: VisitRepository,
        val overrides: SeatOverrideRepository,
    )

    /** Creates the repositories for one test. */
    protected abstract fun createRepositories(): Repositories

    /** Stores [visit] as it is (no seat check), like data from an import or an older build. */
    protected abstract suspend fun insertUnchecked(visit: Visit)

    /** Stores [override] as it is (no seat check), like data from an import or an older build. */
    protected abstract suspend fun insertUnchecked(override: SeatOverride)

    private lateinit var repositories: Repositories
    private val cinemas get() = repositories.cinemas
    private val halls get() = repositories.halls
    private val visits get() = repositories.visits
    private val overrides get() = repositories.overrides

    @Before
    fun createTheRepositories() {
        repositories = createRepositories()
    }

    private val date = LocalDate.of(2026, 10, 13)

    /** 3 × 4 seats, ids 1..12; seat 12 is the last of row 3. */
    private val grid = SeatLayout.create(3, 4, firstSeatId = 1)

    private suspend fun hallWith(layout: SeatLayout?): Hall {
        val hallId = halls.createHall(cinemas.createCinema("Kino"), "Saal 1")
        if (layout != null) assertTrue(halls.updateHall(halls.getHall(hallId)!!.copy(layout = layout)))
        return halls.getHall(hallId)!!
    }

    private suspend fun storedOverrides(hallId: Long): List<SeatOverride> =
        halls.observeHallDetail(hallId).first()!!.overrides

    private fun assertRejected(block: suspend () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { block() } }
    }

    @Test
    fun seatIdsOfRemovedSeatsAreNeverReused() = runTest {
        val hall = hallWith(grid)
        assertEquals(13, hall.nextSeatId)

        // A new grid numbered from 1 would give old ids to other seats.
        assertRejected { halls.updateHall(hall.copy(layout = SeatLayout.create(2, 6, firstSeatId = 1))) }
        assertEquals(grid, halls.getHall(hall.id)!!.layout)

        // Seat 12 removed: the mark stays, so the seat can't come back with its old id ...
        assertTrue(halls.updateHall(hall.copy(layout = grid.withCellType(2, 3, CellType.GAP))))
        val removed = halls.getHall(hall.id)!!
        assertEquals(13, removed.nextSeatId)
        assertRejected { halls.updateHall(removed.copy(layout = grid)) }
        // ... while an edit session gives it a new one.
        val redrawn = removed.startLayoutEdit().setCellType(2, 3, CellType.SEAT).layout!!
        assertEquals(SeatId(13), redrawn.rows[2].seatIds.last())
        assertTrue(halls.updateHall(removed.copy(layout = redrawn)))
        assertEquals(14, halls.getHall(hall.id)!!.nextSeatId)
        assertEquals(14, halls.observeHallDetail(hall.id).first()!!.hall.nextSeatId)
    }

    @Test
    fun theIdMarkCoversSeatIdsThatVisitsAndOverridesUse() = runTest {
        val hall = hallWith(null)
        assertEquals(SeatLayout.FIRST_SEAT_ID, hall.nextSeatId)
        insertUnchecked(Visit(hallId = hall.id, date = date, seatId = SeatId(40)))
        insertUnchecked(SeatOverride(hall.id, SeatId(50), SeatColour.RED))

        // Every read gives the full mark ...
        assertEquals(51, halls.getHall(hall.id)!!.nextSeatId)
        assertEquals(51, halls.observeHallDetail(hall.id).first()!!.hall.nextSeatId)
        assertEquals(51, cinemas.observeCinemaDetail(hall.cinemaId).first()!!.halls.single().hall.nextSeatId)

        // ... a grid below it is refused, one from the mark is saved, and the mark moves on.
        assertRejected { halls.updateHall(halls.getHall(hall.id)!!.copy(layout = SeatLayout.create(5, 8, firstSeatId = 1))) }
        val fresh = halls.getHall(hall.id)!!.startLayoutEdit().newGrid(5, 8).layout!!
        assertTrue(fresh.seatIds.all { it.value >= 51 })
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = fresh)))
        assertEquals(fresh, halls.getHall(hall.id)!!.layout)
        assertEquals(91, halls.getHall(hall.id)!!.nextSeatId)
    }

    @Test
    fun overridesNeedASeatOfTheCurrentLayoutAndAHallHasOneFavourite() = runTest {
        val hall = hallWith(grid)
        assertRejected { overrides.setSeatColour(hall.id, SeatId(99), SeatColour.RED) }
        assertRejected { overrides.setFavouriteSeat(hall.id, SeatId(99)) }
        assertRejected { overrides.setFavouriteSeat(12_345, null) }

        overrides.setSeatColour(hall.id, SeatId(2), SeatColour.RED)
        overrides.setFavouriteSeat(hall.id, SeatId(2))
        overrides.setFavouriteSeat(hall.id, SeatId(3)) // moves: one favourite per hall
        assertEquals(
            listOf(SeatOverride(hall.id, SeatId(2), SeatColour.RED), SeatOverride(hall.id, SeatId(3), isFavourite = true)),
            storedOverrides(hall.id),
        )
        assertEquals(SeatId(3), halls.observeHallDetail(hall.id).first()!!.favouriteSeat)
        assertEquals(
            grid.labels().labelOf(SeatId(3)),
            cinemas.observeCinemaDetail(hall.cinemaId).first()!!.halls.single().favouriteSeat,
        )

        overrides.setFavouriteSeat(hall.id, null)
        overrides.clearSeatOverride(hall.id, SeatId(2))
        assertTrue(storedOverrides(hall.id).isEmpty())
        assertNull(halls.observeHallDetail(hall.id).first()!!.favouriteSeat)
    }

    @Test
    fun layoutEditsPruneOnlyOverridesOfRemovedSeats() = runTest {
        val hall = hallWith(grid)
        overrides.setSeatColour(hall.id, SeatId(1), SeatColour.GREEN)
        overrides.setFavouriteSeat(hall.id, SeatId(4))

        // Edits that keep the layout keep every override.
        assertTrue(halls.renameHall(hall.id, "Saal 2"))
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(notes = "notes", screenWidth = 4f, screenDistance = 2f)))
        assertEquals(2, storedOverrides(hall.id).size)

        // Removing seat 4 drops its favourite, not seat 1's colour.
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = grid.withCellType(0, 3, CellType.GAP))))
        assertEquals(listOf(SeatOverride(hall.id, SeatId(1), SeatColour.GREEN)), storedOverrides(hall.id))

        // Clearing the layout drops them all.
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = null)))
        assertTrue(storedOverrides(hall.id).isEmpty())
    }

    @Test
    fun aSeatNotInTheLayoutIsClearedOnSaveUnlessItIsTheVisitsUnchangedSeat() = runTest {
        val hall = hallWith(grid)
        val unknown = visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(99)))
        assertTrue(unknown.seatCleared)
        assertNull(visits.getVisit(unknown.visitId)!!.seatId)

        val saved = visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(12)))
        assertFalse(saved.seatCleared)
        // Seat 12 is removed from the layout: saving that visit again keeps its seat ...
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = grid.withCellType(2, 3, CellType.GAP))))
        val visit = visits.getVisit(saved.visitId)!!
        assertFalse(visits.saveVisit(visit.copy(filmTitle = "Film")).seatCleared)
        assertEquals(SeatId(12), visits.getVisit(saved.visitId)!!.seatId)
        // ... but another visit can't pick it.
        assertTrue(visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(12))).seatCleared)
    }

    @Test
    fun deletingAUserCinemaOrHallTakesItsHallsAndVisitsWithIt() = runTest {
        val hall = hallWith(grid)
        val other = hallWith(grid) // in a cinema of its own
        val visit = visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(1))).visitId
        val kept = visits.saveVisit(Visit(hallId = other.id, date = date, seatId = SeatId(1))).visitId
        // Seat colours and favourites don't keep a cinema or hall from being deleted.
        overrides.setFavouriteSeat(hall.id, SeatId(1))
        overrides.setSeatColour(other.id, SeatId(1), SeatColour.RED)

        assertEquals(DeleteResult.DELETED, cinemas.deleteCinema(hall.cinemaId))
        assertNull(cinemas.observeCinemaDetail(hall.cinemaId).first())
        assertNull(halls.getHall(hall.id))
        assertNull(visits.getVisit(visit))
        assertEquals(listOf(kept), visits.observeAllVisits().first().map { it.visit.id })
        assertEquals(DeleteResult.NOT_FOUND, cinemas.deleteCinema(hall.cinemaId))

        assertEquals(DeleteResult.DELETED, halls.deleteHall(other.id))
        assertNull(visits.getVisit(kept))
        assertTrue(visits.observeAllVisits().first().isEmpty())
        assertTrue(cinemas.observeCinemaDetail(other.cinemaId).first()!!.halls.isEmpty())
        assertEquals(DeleteResult.NOT_FOUND, halls.deleteHall(other.id))
    }

    @Test
    fun favouriteCinemasAreStoredReadEverywhereAndKeptByEdits() = runTest {
        val first = cinemas.createCinema("Kino 10")
        val second = cinemas.createCinema("Kino 2")
        assertFalse(cinemas.observeCinemaDetail(first).first()!!.cinema.isFavourite)

        assertTrue(cinemas.setFavourite(first, true))
        assertTrue(cinemas.setFavourite(second, true)) // any number of favourites
        assertTrue(cinemas.setFavourite(second, true)) // again: nothing changes
        // In natural name order: "Kino 2" before "Kino 10".
        assertEquals(listOf(second, first), cinemas.observeCinemas().first().filter { it.cinema.isFavourite }.map { it.cinema.id })
        assertEquals(listOf(second, first), cinemas.observeCinemaDetails().first().filter { it.cinema.isFavourite }.map { it.cinema.id })

        assertTrue(cinemas.updateCinema(first, "Kino 1", "Street", null))
        assertTrue(cinemas.renameCinema(second, "Kino 3"))
        assertTrue(cinemas.observeCinemaDetail(first).first()!!.cinema.isFavourite)
        assertTrue(cinemas.observeCinemaDetail(second).first()!!.cinema.isFavourite)

        assertTrue(cinemas.setFavourite(first, false))
        assertFalse(cinemas.observeCinemaDetail(first).first()!!.cinema.isFavourite)
        assertFalse(cinemas.setFavourite(999_999, true))
    }
}
