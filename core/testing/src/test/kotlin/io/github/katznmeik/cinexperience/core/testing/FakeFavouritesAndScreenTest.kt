package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Favourite cinemas and the screen offset behave in the fake like in Room (see
 * `CinemaRepositoryTest` and `HallRepositoryTest` in core:data).
 */
class FakeFavouritesAndScreenTest {

    private val hall = Hall(id = 10, cinemaId = 1, name = "Saal 1", layout = SeatLayout.fromPatterns("SSSS", "SSSS", firstSeatId = 1))

    private fun repository() = FakeCinexRepository(
        FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino"), Cinema(id = 2, name = "Other")), halls = listOf(hall)),
    )

    @Test
    fun favouritesAreStoredReadEverywhereAndSurviveEdits() = runTest {
        val repo = repository()
        val created = repo.createCinema("New")
        assertFalse(repo.observeCinemaDetail(created).first()!!.cinema.isFavourite)

        assertTrue(repo.setFavourite(1, true))
        assertTrue(repo.setFavourite(created, true))
        assertEquals(setOf(1L, created), repo.observeCinemas().first().filter { it.cinema.isFavourite }.map { it.cinema.id }.toSet())
        assertEquals(setOf(1L, created), repo.observeCinemaDetails().first().filter { it.cinema.isFavourite }.map { it.cinema.id }.toSet())

        assertTrue(repo.updateCinema(1, "Kino 2", "Street", null))
        assertTrue(repo.renameCinema(created, "Newer"))
        assertTrue(repo.observeCinemaDetail(1).first()!!.cinema.isFavourite)
        assertTrue(repo.observeCinemaDetail(created).first()!!.cinema.isFavourite)

        assertTrue(repo.setFavourite(1, false))
        assertFalse(repo.observeCinemaDetail(1).first()!!.cinema.isFavourite)
        assertFalse(repo.setFavourite(999, true))

        repo.failure = IllegalStateException("disk full")
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.setFavourite(2, true) } }
        assertFalse(repo.observeCinemaDetail(2).first()!!.cinema.isFavourite)
    }

    @Test
    fun screenOffsetIsSavedKeptByOtherEditsAndValidated() = runTest {
        val repo = repository()
        assertEquals(0f, repo.getHall(10)!!.screenOffset)
        assertTrue(repo.updateHall(repo.getHall(10)!!.copy(screenWidth = 3f, screenDistance = 1f, screenOffset = -1.5f)))
        assertEquals(-1.5f, repo.observeHallDetail(10).first()!!.hall.screenOffset)
        assertEquals(-1.5f, repo.observeCinemaDetail(1).first()!!.halls.single().hall.screenOffset)

        assertTrue(repo.renameHall(10, "Saal 2"))
        val stored = repo.getHall(10)!!
        assertTrue(repo.updateHall(stored.copy(layout = stored.startLayoutEdit().removeRow(1).layout, notes = "x")))
        assertEquals(-1.5f, repo.getHall(10)!!.screenOffset)

        for (bad in listOf(0.25f, 30.5f, -100f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val before = repo.getHall(10)!!
            assertThrows("$bad", IllegalArgumentException::class.java) {
                runBlocking { repo.updateHall(before.copy(screenOffset = bad, notes = "changed")) }
            }
            assertEquals(before, repo.getHall(10))
        }

        assertTrue(repo.updateHall(repo.getHall(10)!!.copy(screenOffset = -0f)))
        assertEquals(0f.toRawBits(), repo.getHall(10)!!.screenOffset.toRawBits())
    }
}
