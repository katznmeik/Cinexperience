package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

internal class CinemaRepositoryTest : DatabaseTest() {

    private val date = LocalDate.of(2026, 10, 1)

    private suspend fun preloadedCinema(name: String = "Preloaded"): Long = db.cinemaDao().insert(
        CinemaEntity(name = name, address = null, notes = null, isPreloaded = true, seedKey = name.lowercase()),
    )

    private suspend fun preloadedHall(cinemaId: Long): Long = db.hallDao().insert(
        HallEntity(
            cinemaId = cinemaId, name = "Saal 1", layoutJson = null, screenWidth = null, screenDistance = null,
            referenceImagePath = null, notes = null, isPreloaded = true, seedKey = "p/saal-1",
        ),
    )

    @Test
    fun createUpdateAndRename() = runTest {
        val id = cinemas.createCinema("  Kino  ", address = " ", notes = " note ")
        var cinema = cinemas.observeCinemaDetail(id).first()!!.cinema
        assertEquals("Kino", cinema.name)
        assertNull(cinema.address)
        assertEquals("note", cinema.notes)
        assertFalse(cinema.isPreloaded)
        assertNull(cinema.seedKey)

        assertTrue(cinemas.updateCinema(id, "Kino 2", "Street 1", null))
        cinema = cinemas.observeCinemaDetail(id).first()!!.cinema
        assertEquals("Street 1", cinema.address)
        assertNull(cinema.notes)

        assertTrue(cinemas.renameCinema(id, "Kino 3"))
        assertEquals("Kino 3", cinemas.observeCinemaDetail(id).first()!!.cinema.name)
        assertEquals("Street 1", cinemas.observeCinemaDetail(id).first()!!.cinema.address)

        assertFalse(cinemas.renameCinema(999, "X"))
        assertNull(cinemas.observeCinemaDetail(999).first())
    }

    @Test
    fun blankNamesAreRejected() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { cinemas.createCinema(" ") } }
        val id = cinemas.createCinema("A")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { cinemas.renameCinema(id, "") } }
    }

    @Test
    fun renamingPreloadedCinemaKeepsItsSeedIdentity() = runTest {
        val id = preloadedCinema()
        assertTrue(cinemas.renameCinema(id, "Renamed"))
        val cinema = cinemas.observeCinemaDetail(id).first()!!.cinema
        assertEquals("Renamed", cinema.name)
        assertTrue(cinema.isPreloaded)
        assertEquals("preloaded", cinema.seedKey)
    }

    @Test
    fun preloadedCinemaCannotBeDeleted() = runTest {
        val id = preloadedCinema()
        assertEquals(DeleteResult.PRELOADED, cinemas.deleteCinema(id))
        assertEquals(1, cinemas.observeCinemas().first().size)
        assertEquals(DeleteResult.NOT_FOUND, cinemas.deleteCinema(12345))
    }

    @Test
    fun deletingCinemaCascadesToHallsVisitsAndOverrides() = runTest {
        val cinemaId = cinemas.createCinema("Mine")
        val hallId = halls.createHall(cinemaId, "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = layoutOf("SS")))
        val visitId = visits.save(Visit(hallId = hallId, date = date, seatId = SeatId(1)))
        overrides.setFavouriteSeat(hallId, SeatId(1))

        assertEquals(DeleteResult.DELETED, cinemas.deleteCinema(cinemaId))

        assertNull(halls.getHall(hallId))
        assertNull(visits.getVisit(visitId))
        assertTrue(db.seatOverrideDao().getForHall(hallId).isEmpty())
        assertTrue(cinemas.observeCinemas().first().isEmpty())
    }

    @Test
    fun cinemaListIsSortedNaturallyAndCaseInsensitively() = runTest {
        listOf("Rollberg", "delphi LUX", "Babylon", "Kino 10", "Kino 2", "Delphi Filmpalast").forEach {
            cinemas.createCinema(it)
        }
        val expected = listOf("Babylon", "Delphi Filmpalast", "delphi LUX", "Kino 2", "Kino 10", "Rollberg")
        assertEquals(expected, cinemas.observeCinemas().first().map { it.cinema.name })
        assertEquals(expected, cinemas.observeCinemaDetails().first().map { it.cinema.name })
    }

    @Test
    fun summaryAndDetailUseSpecAggregation() = runTest {
        val cinemaId = cinemas.createCinema("Kino")
        val hall1 = halls.createHall(cinemaId, "Saal 2")
        val hall2 = halls.createHall(cinemaId, "Saal 10")
        val hall3 = halls.createHall(cinemaId, "Saal 1") // no visits
        halls.updateHall(halls.getHall(hall1)!!.copy(layout = layoutOf("SSS")))
        visits.save(
            Visit(
                hallId = hall1, date = date,
                hallRatings = HallRatings(sound = Rating(4), picture = Rating(2)),
                cinemaRatings = CinemaRatings(atmosphere = Rating(5)),
            ),
        )
        visits.save(Visit(hallId = hall2, date = date, hallRatings = HallRatings(legroom = Rating(5))))
        visits.save(Visit(hallId = hall2, date = date)) // unrated
        overrides.setFavouriteSeat(hall1, SeatId(3))

        val summary = cinemas.observeCinemas().first().single()
        // Hall 1 overall 3.0, hall 2 overall 5.0, atmosphere 5.0 → (5 + 3 + 5) / 3.
        assertEquals(13.0 / 3, summary.overallScore!!, 1e-9)
        assertEquals(3, summary.visitCount)
        assertEquals(3, summary.hallCount)

        val detail = cinemas.observeCinemaDetail(cinemaId).first()!!
        assertEquals(listOf(detail), cinemas.observeCinemaDetails().first())
        assertEquals(5.0, detail.scores[CinemaCategory.ATMOSPHERE]!!.average, 0.0)
        assertEquals(1, detail.scores[CinemaCategory.ATMOSPHERE]!!.count)
        assertNull(detail.scores[CinemaCategory.POPCORN])
        assertEquals(listOf("Saal 1", "Saal 2", "Saal 10"), detail.halls.map { it.hall.name })
        val (h3, h1, h2) = detail.halls
        assertEquals(hall3, h3.hall.id)
        assertNull(h3.overallScore)
        assertEquals(0, h3.visitCount)
        assertEquals(3.0, h1.overallScore!!, 0.0)
        assertEquals(SeatLabel("1", 3), h1.favouriteSeat)
        assertEquals(5.0, h2.overallScore!!, 0.0)
        assertEquals(2, h2.visitCount)
        assertNull(h2.favouriteSeat)
    }

    @Test
    fun cinemaWithoutDataHasNoScore() = runTest {
        val id = preloadedCinema()
        preloadedHall(id)
        val summary = cinemas.observeCinemas().first().single()
        assertNull(summary.overallScore)
        assertEquals(0, summary.visitCount)
        assertEquals(1, summary.hallCount)
        assertTrue(cinemas.observeCinemaDetail(id).first()!!.scores.categories.isEmpty())
    }

    @Test
    fun flowsEmitAgainAfterChanges() = runTest {
        val id = cinemas.createCinema("Kino")
        val hallId = halls.createHall(id, "Saal 1")
        val flow = cinemas.observeCinemas()
        assertEquals(0, flow.first().single().visitCount)
        visits.save(Visit(hallId = hallId, date = date))
        assertEquals(1, flow.first { it.single().visitCount == 1 }.single().visitCount)
    }
}
