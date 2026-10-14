package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake's seat overrides behave like the Room repository (see
 * `VisitAndOverrideRepositoryTest`, `HallRepositoryTest` and `UnreadableLayoutTest`
 * in core:data), so ViewModel tests see the same results.
 */
class FakeOverridesTest {

    private val cinema = Cinema(id = 1, name = "Kino")
    private val layout = SeatLayout.fromPatterns("SSS", firstSeatId = 1)

    private val drawnHall = Hall(id = 10, cinemaId = 1, name = "Saal 1", layout = layout, nextSeatId = 4)

    private fun repository(hall: Hall = drawnHall, store: FakeStore.() -> FakeStore = { this }) =
        FakeCinexRepository(FakeStore(cinemas = listOf(cinema), halls = listOf(hall)).store())

    private suspend fun FakeCinexRepository.overrides(): List<SeatOverride> = observeHallDetail(10).first()!!.overrides

    @Test
    fun colourAndFavouriteAreStoredBySeatId() = runTest {
        val repo = repository()
        repo.setSeatColour(10, SeatId(2), SeatColour.RED)
        repo.setFavouriteSeat(10, SeatId(2))
        repo.setFavouriteSeat(10, SeatId(3)) // moves
        assertEquals(
            listOf(SeatOverride(10, SeatId(2), SeatColour.RED), SeatOverride(10, SeatId(3), isFavourite = true)),
            repo.overrides(),
        )
        assertEquals(SeatLabel("1", 3), repo.observeCinemaDetail(1).first()!!.halls.single().favouriteSeat)
        repo.clearSeatOverride(10, SeatId(2))
        repo.setFavouriteSeat(10, null)
        assertTrue(repo.overrides().isEmpty())
    }

    @Test
    fun overridesRequireASeatOfTheCurrentLayout() {
        val repo = repository()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.setSeatColour(10, SeatId(99), SeatColour.RED) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.setFavouriteSeat(12345, null) } }
    }

    @Test
    fun layoutEditsPruneOnlyOverridesOfRemovedSeats() = runTest {
        val repo = repository()
        repo.setSeatColour(10, SeatId(1), SeatColour.GREEN)
        repo.setFavouriteSeat(10, SeatId(3))
        repo.renameHall(10, "Saal 2")
        assertEquals(2, repo.overrides().size) // unchanged layout: nothing dropped

        val hall = repo.getHall(10)!!
        repo.updateHall(hall.copy(layout = layout.withCellType(0, 2, CellType.GAP)))
        assertEquals(listOf(SeatOverride(10, SeatId(1), SeatColour.GREEN)), repo.overrides())

        // Clearing the layout drops every override.
        repo.updateHall(repo.getHall(10)!!.copy(layout = null))
        assertTrue(repo.store.value.overrides.isEmpty())
    }

    @Test
    fun renamingAHallWithAnUnreadableLayoutKeepsItsOverrides() = runTest {
        val unreadable = Hall(id = 10, cinemaId = 1, name = "Saal 1", layout = null, layoutUnreadable = true, nextSeatId = 4)
        val favourite = SeatOverride(10, SeatId(1), SeatColour.RED, isFavourite = true)
        val repo = repository(unreadable) { copy(overrides = listOf(favourite)) }
        repo.renameHall(10, "Renamed")
        assertEquals(listOf(favourite), repo.store.value.overrides)
        assertEquals("Renamed", repo.getHall(10)!!.name)
    }

    @Test
    fun aLabelFavouriteDoesNotJumpToANewSeatWithTheSameLabel() = runTest {
        // Fixture shortcut: favourite "Row 1, Seat 1" = seat 1 of the stored layout.
        val repo = repository { copy(favourites = mapOf(10L to SeatLabel("1", 1))) }
        assertEquals(SeatId(1), repo.observeHallDetail(10).first()!!.favouriteSeat)

        // A new grid: fresh ids, and again a seat labelled "Row 1, Seat 1".
        val hall = repo.getHall(10)!!
        val newGrid = hall.startLayoutEdit().newGrid(1, 3).layout!!
        repo.updateHall(hall.copy(layout = newGrid))
        val detail = repo.observeHallDetail(10).first()!!
        assertNull(detail.favouriteSeat)
        assertNull(repo.observeCinemaDetail(1).first()!!.halls.single().favouriteSeat)
    }
}
