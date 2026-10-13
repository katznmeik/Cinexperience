package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatColourSource
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.removedSeatsWithData
import io.github.katznmeik.cinexperience.core.model.seatsWithData
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

internal class HallRepositoryTest : DatabaseTest() {

    private suspend fun newHall(layout: SeatLayout? = null): Long {
        val cinemaId = cinemas.createCinema("Kino")
        val hallId = halls.createHall(cinemaId, "Saal 1")
        if (layout != null) halls.updateHall(halls.getHall(hallId)!!.copy(layout = layout))
        return hallId
    }

    private fun visit(hallId: Long, day: Int, seat: Int? = null, seatRating: Int? = null, sound: Int? = null) = Visit(
        hallId = hallId,
        date = LocalDate.of(2026, 1, day),
        seatId = seat?.let(::SeatId),
        seatRating = Rating.ofOrNull(seatRating),
        hallRatings = HallRatings(sound = Rating.ofOrNull(sound)),
    )

    @Test
    fun createHallRequiresExistingCinema() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { halls.createHall(42, "Saal") } }
        val hall = halls.getHall(newHall())!!
        assertEquals("Saal 1", hall.name)
        assertFalse(hall.isPreloaded)
        assertNull(hall.layout)
    }

    @Test
    fun updateHallSavesEditableFieldsOnly() = runTest {
        val hallId = newHall()
        val original = halls.getHall(hallId)!!
        val layout = layoutOf("SS|SS")
        assertTrue(
            halls.updateHall(
                original.copy(
                    name = " Saal A ",
                    layout = layout,
                    screenWidth = 6f,
                    screenDistance = 1.5f,
                    referenceImagePath = "reference/hall-1.jpg",
                    notes = "Nice",
                    isPreloaded = true, // ignored
                    seedKey = "hack", // ignored
                ),
            ),
        )
        val saved = halls.getHall(hallId)!!
        assertEquals("Saal A", saved.name)
        assertEquals(layout, saved.layout)
        assertEquals(6f, saved.screenWidth)
        assertEquals(1.5f, saved.screenDistance)
        assertEquals("reference/hall-1.jpg", saved.referenceImagePath)
        assertEquals("Nice", saved.notes)
        assertFalse(saved.isPreloaded)
        assertNull(saved.seedKey)
        assertFalse(halls.updateHall(original.copy(id = 999)))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { halls.updateHall(saved.copy(cinemaId = saved.cinemaId + 1)) }
        }
    }

    @Test
    fun hallDetailCombinesLayoutScoresVisitsAndSeatStates() = runTest {
        val hallId = newHall(layoutOf("SS|SS"))
        visits.save(visit(hallId, day = 1, seat = 1, seatRating = 5, sound = 4))
        visits.save(visit(hallId, day = 3, seat = 1, seatRating = 3))
        visits.save(visit(hallId, day = 2, seat = 2, seatRating = 1, sound = 2))
        overrides.setSeatColour(hallId, SeatId(2), SeatColour.GREEN)
        overrides.setFavouriteSeat(hallId, SeatId(4))

        val detail = halls.observeHallDetail(hallId).first()!!
        assertEquals("Kino", detail.cinema.name)
        assertEquals(listOf(3, 2, 1), detail.visits.map { it.date.dayOfMonth })
        assertEquals(3.0, detail.scores[HallCategory.SOUND]!!.average, 0.0)
        assertEquals(2, detail.scores[HallCategory.SOUND]!!.count)
        assertEquals(3.0, detail.scores.overall!!, 0.0)
        assertEquals(3, detail.scores.visitCount)
        assertEquals(SeatLabel("1", 3), detail.labels!!.labelOf(SeatId(3)))

        assertEquals(4, detail.seats.size)
        val seat1 = detail.seats.getValue(SeatId(1))
        assertEquals(SeatColour.GREEN, seat1.colour) // average 4.0
        assertEquals(2, seat1.visitCount)
        val seat2 = detail.seats.getValue(SeatId(2))
        assertEquals(SeatColour.GREEN, seat2.colour) // override beats the red average
        assertEquals(SeatColourSource.OVERRIDE, seat2.colourSource)
        assertEquals(SeatColour.RED, seat2.automaticColour)
        assertNull(detail.seats.getValue(SeatId(3)).colour)
        assertEquals(SeatId(4), detail.favouriteSeat)
        assertTrue(detail.seats.getValue(SeatId(4)).isFavourite)
    }

    @Test
    fun hallWithoutLayoutHasNoSeats() = runTest {
        val detail = halls.observeHallDetail(newHall()).first()!!
        assertNull(detail.labels)
        assertTrue(detail.seats.isEmpty())
        assertNull(detail.scores.overall)
        assertNull(halls.observeHallDetail(999).first())
    }

    @Test
    fun layoutEditsKeepSeatDataAndPruneOverridesOfRemovedSeats() = runTest {
        val old = layoutOf("SSS")
        val hallId = newHall(old)
        val visitId = visits.save(visit(hallId, day = 1, seat = 3, seatRating = 5))
        overrides.setFavouriteSeat(hallId, SeatId(1))
        overrides.setSeatColour(hallId, SeatId(2), SeatColour.RED)

        // Adding a row in front and relabelling keeps everything attached.
        val grown = old.insertRow(0).updateRow(1) { it.copy(label = "B") }
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = grown))
        var detail = halls.observeHallDetail(hallId).first()!!
        assertEquals(SeatLabel("B", 3), detail.seats.getValue(SeatId(3)).label)
        assertEquals(SeatColour.GREEN, detail.seats.getValue(SeatId(3)).colour)
        assertEquals(SeatId(1), detail.favouriteSeat)

        // Removing the favourite seat: the warning lists it, saving drops its override.
        val shrunk = grown.withCellType(1, 0, CellType.GAP)
        val data = seatsWithData(detail.visits, detail.overrides)
        assertEquals(listOf(SeatId(1)), removedSeatsWithData(grown, shrunk, data).map { it.seatId })
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = shrunk))
        detail = halls.observeHallDetail(hallId).first()!!
        assertNull(detail.favouriteSeat)
        assertEquals(listOf(SeatOverride(hallId, SeatId(2), SeatColour.RED)), detail.overrides)

        // Clearing the layout drops all overrides but keeps visits and their seat id.
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = null))
        assertTrue(db.seatOverrideDao().getForHall(hallId).isEmpty())
        assertEquals(SeatId(3), visits.getVisit(visitId)!!.seatId)
    }

    @Test
    fun preloadedHallCannotBeDeleted() = runTest {
        val cinemaId = cinemas.createCinema("Kino")
        val id = db.hallDao().insert(
            HallEntity(
                cinemaId = cinemaId, name = "Saal 1", layoutJson = null, screenWidth = null, screenDistance = null,
                referenceImagePath = null, notes = null, isPreloaded = true, seedKey = "kino/saal-1",
            ),
        )
        assertEquals(DeleteResult.PRELOADED, halls.deleteHall(id))
        assertTrue(halls.renameHall(id, "Großer Saal"))
        assertEquals("Großer Saal", halls.getHall(id)!!.name)
        assertEquals(DeleteResult.NOT_FOUND, halls.deleteHall(999))
    }

    @Test
    fun deletingHallCascadesToVisitsAndOverrides() = runTest {
        val hallId = newHall(layoutOf("SS"))
        val cinemaId = halls.getHall(hallId)!!.cinemaId
        val otherHall = halls.createHall(cinemaId, "Saal 2")
        val visitId = visits.save(visit(hallId, day = 1, seat = 1))
        val otherVisit = visits.save(visit(otherHall, day = 1))
        overrides.setSeatColour(hallId, SeatId(2), SeatColour.YELLOW)

        assertEquals(DeleteResult.DELETED, halls.deleteHall(hallId))

        assertNull(halls.getHall(hallId))
        assertNull(visits.getVisit(visitId))
        assertTrue(db.seatOverrideDao().getForHall(hallId).isEmpty())
        assertEquals(otherHall, visits.getVisit(otherVisit)!!.hallId)
        assertEquals(1, cinemas.observeCinemaDetail(cinemaId).first()!!.halls.size)
    }
}
