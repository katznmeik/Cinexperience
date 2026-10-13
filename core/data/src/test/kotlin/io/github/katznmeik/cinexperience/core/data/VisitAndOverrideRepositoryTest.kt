package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
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

internal class VisitAndOverrideRepositoryTest : DatabaseTest() {

    private suspend fun hallWithLayout(cinema: String = "Kino", layout: SeatLayout = SeatLayout.fromPatterns("SS|SS")): Long {
        val cinemaId = cinemas.createCinema(cinema)
        val hallId = halls.createHall(cinemaId, "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = layout))
        return hallId
    }

    @Test
    fun visitRoundTripsEveryField() = runTest {
        val hallId = hallWithLayout()
        val visit = Visit(
            hallId = hallId,
            date = LocalDate.of(2026, 10, 13),
            filmTitle = "  Film  ",
            seatId = SeatId(3),
            screeningFormat = ScreeningFormat.SEVENTY_MM,
            hallRatings = HallRatings(Rating(1), Rating(2), Rating(3), Rating(4), Rating(5), Rating(1)),
            seatRating = Rating(4),
            cinemaRatings = CinemaRatings(Rating(5), Rating(4), Rating(3), Rating(2)),
            note = "note",
            photoPath = "photos/1.jpg",
        )
        val id = visits.saveVisit(visit)
        assertEquals(visit.copy(id = id, filmTitle = "Film"), visits.getVisit(id))
        assertEquals(visit.copy(id = id, filmTitle = "Film"), visits.observeVisit(id).first())
    }

    @Test
    fun unratedVisitStaysUnrated() = runTest {
        val hallId = hallWithLayout()
        val id = visits.saveVisit(Visit(hallId = hallId, date = LocalDate.of(2026, 1, 1), filmTitle = " "))
        val saved = visits.getVisit(id)!!
        assertEquals(HallRatings.NONE, saved.hallRatings)
        assertEquals(CinemaRatings.NONE, saved.cinemaRatings)
        assertNull(saved.seatRating)
        assertNull(saved.filmTitle)
        assertNull(saved.screeningFormat)
    }

    @Test
    fun updateAndDeleteVisit() = runTest {
        val hallId = hallWithLayout()
        val id = visits.saveVisit(Visit(hallId = hallId, date = LocalDate.of(2026, 1, 1)))
        assertEquals(id, visits.saveVisit(visits.getVisit(id)!!.copy(seatRating = Rating(2))))
        assertEquals(Rating(2), visits.getVisit(id)!!.seatRating)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { visits.saveVisit(Visit(id = 999, hallId = hallId, date = LocalDate.of(2026, 1, 1))) }
        }
        assertTrue(visits.deleteVisit(id))
        assertFalse(visits.deleteVisit(id))
        assertNull(visits.observeVisit(id).first())
    }

    @Test
    fun allVisitsAreNewestFirstWithNamesAndSeatLabels() = runTest {
        val hallA = hallWithLayout("Alpha")
        val hallB = hallWithLayout("Beta", SeatLayout.fromPatterns("SSS").updateRow(0) { it.copy(label = "K") })
        visits.saveVisit(Visit(hallId = hallA, date = LocalDate.of(2026, 1, 5), seatId = SeatId(3)))
        visits.saveVisit(Visit(hallId = hallB, date = LocalDate.of(2026, 2, 1), seatId = SeatId(2)))
        visits.saveVisit(Visit(hallId = hallA, date = LocalDate.of(2026, 1, 5)))
        visits.saveVisit(Visit(hallId = hallB, date = LocalDate.of(2025, 12, 31), seatId = SeatId(99)))

        val list = visits.observeAllVisits().first()
        assertEquals(
            listOf("2026-02-01", "2026-01-05", "2026-01-05", "2025-12-31"),
            list.map { it.visit.date.toString() },
        )
        assertEquals("Beta", list[0].cinemaName)
        assertEquals("Saal 1", list[0].hallName)
        assertEquals(SeatLabel("K", 2), list[0].seatLabel)
        // Same date: the later-saved visit first.
        assertNull(list[1].seatLabel)
        assertEquals(SeatLabel("1", 3), list[2].seatLabel) // first seat after the aisle, continuous numbering
        assertNull(list[3].seatLabel) // seat no longer in the layout
        assertEquals(halls.getHall(hallA)!!.cinemaId, list[1].cinemaId)
    }

    @Test
    fun favouriteIsUniquePerHall() = runTest {
        val hallId = hallWithLayout()
        val otherHall = hallWithLayout("Other")
        overrides.setFavouriteSeat(otherHall, SeatId(1))
        overrides.setFavouriteSeat(hallId, SeatId(1))
        overrides.setSeatColour(hallId, SeatId(2), SeatColour.RED)
        overrides.setFavouriteSeat(hallId, SeatId(2))

        val stored = db.seatOverrideDao().getForHall(hallId)
        assertEquals(1, stored.count { it.isFavourite })
        assertEquals(listOf(SeatOverride(hallId, SeatId(2), SeatColour.RED, isFavourite = true)), halls.observeHallDetail(hallId).first()!!.overrides)
        // The other hall keeps its own favourite.
        assertEquals(SeatId(1), halls.observeHallDetail(otherHall).first()!!.favouriteSeat)

        overrides.setFavouriteSeat(hallId, null)
        assertNull(halls.observeHallDetail(hallId).first()!!.favouriteSeat)
        assertEquals(SeatColour.RED, halls.observeHallDetail(hallId).first()!!.seats.getValue(SeatId(2)).colour)
    }

    @Test
    fun colourOverrideSetAndClear() = runTest {
        val hallId = hallWithLayout()
        overrides.setSeatColour(hallId, SeatId(1), SeatColour.YELLOW)
        assertEquals(SeatColour.YELLOW, halls.observeHallDetail(hallId).first()!!.seats.getValue(SeatId(1)).colour)
        overrides.setSeatColour(hallId, SeatId(1), null)
        assertTrue(db.seatOverrideDao().getForHall(hallId).isEmpty())

        overrides.setSeatColour(hallId, SeatId(1), SeatColour.RED)
        overrides.setFavouriteSeat(hallId, SeatId(1))
        overrides.clearSeatOverride(hallId, SeatId(1))
        assertTrue(db.seatOverrideDao().getForHall(hallId).isEmpty())
    }

    @Test
    fun overridesRequireASeatOfTheCurrentLayout() = runTest {
        val hallId = hallWithLayout()
        val noLayout = halls.createHall(cinemas.createCinema("Empty"), "Saal")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { overrides.setSeatColour(hallId, SeatId(99), SeatColour.RED) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { overrides.setFavouriteSeat(noLayout, SeatId(1)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { overrides.setFavouriteSeat(12345, null) }
        }
    }
}
