package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.db.toEntity
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.seatsWithData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Seat ids must never be reused within a hall, even when its layout is replaced. */
internal class SeatIdMarkTest : DatabaseTest() {

    private val date = LocalDate.of(2026, 10, 13)

    /** Hall with a 3×4 grid (ids 1..12), a rated visit and the favourite on seat 12. */
    private suspend fun hallWithDataOnSeat12(): Long {
        val hallId = halls.createHall(cinemas.createCinema("Kino"), "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = SeatLayout.create(3, 4, firstSeatId = 1)))
        visits.save(Visit(hallId = hallId, date = date, seatId = SeatId(12), seatRating = Rating(5)))
        overrides.setFavouriteSeat(hallId, SeatId(12))
        return hallId
    }

    @Test
    fun replacingTheGridWithIdsFromOneIsRejected() = runTest {
        val hallId = hallWithDataOnSeat12()
        val hall = halls.getHall(hallId)!!
        assertEquals(13, hall.nextSeatId)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { halls.updateHall(hall.copy(layout = SeatLayout.create(2, 6, firstSeatId = 1))) }
        }
        // Nothing changed.
        assertEquals(SeatLayout.create(3, 4, firstSeatId = 1), halls.getHall(hallId)!!.layout)
    }

    @Test
    fun redrawingViaEditSessionWarnsAndDoesNotReattachData() = runTest {
        val hallId = hallWithDataOnSeat12()
        val detail = halls.observeHallDetail(hallId).first()!!
        val session = detail.hall.startLayoutEdit().clear().newGrid(2, 6)

        // The §6.5 warning lists the old seat 12.
        val removed = session.removedSeatsWithData(seatsWithData(detail.visits, detail.overrides))
        assertEquals(listOf(SeatId(12)), removed.map { it.seatId })

        halls.updateHall(detail.hall.copy(layout = session.layout))
        val after = halls.observeHallDetail(hallId).first()!!
        assertTrue(after.hall.layout!!.seatIds.all { it.value >= 13 })
        // The visit keeps pointing at the removed seat: no new seat has its data.
        assertTrue(after.seats.values.all { it.visitCount == 0 && it.averageRating == null && !it.isFavourite })
        assertNull(after.favouriteSeat)
        assertEquals(SeatId(12), after.visits.single().seatId)
        assertNull(visits.observeAllVisits().first().single().seatLabel)
        assertEquals(25, after.hall.nextSeatId)
    }

    @Test
    fun clearingThenRedrawingStillUsesFreshIds() = runTest {
        val hallId = hallWithDataOnSeat12()
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = null))
        val cleared = halls.getHall(hallId)!!
        assertNull(cleared.layout)
        assertEquals(13, cleared.nextSeatId) // the mark survives the cleared layout

        // A grid numbered from 1 would reuse id 12 → rejected.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { halls.updateHall(cleared.copy(layout = SeatLayout.create(2, 6, firstSeatId = 1))) }
        }
        val redrawn = cleared.startLayoutEdit().newGrid(2, 6).layout
        halls.updateHall(cleared.copy(layout = redrawn))
        assertEquals(redrawn, halls.getHall(hallId)!!.layout)
        assertTrue(halls.observeHallDetail(hallId).first()!!.seats.values.all { it.visitCount == 0 })
    }

    @Test
    fun ordinaryEditsOfTheStoredLayoutAreAccepted() = runTest {
        val hallId = hallWithDataOnSeat12()
        val hall = halls.getHall(hallId)!!
        // Toggling seat 12 away and back in one session keeps its id and its data.
        val session = hall.startLayoutEdit()
            .insertRow(0)
            .cycleCell(3, 3).cycleCell(3, 3).cycleCell(3, 3)
            .setCellType(0, 0, CellType.GAP)
        assertEquals(LayoutCell.Seat(SeatId(12)), session.layout!!.cell(3, 3))
        halls.updateHall(hall.copy(layout = session.layout))
        val detail = halls.observeHallDetail(hallId).first()!!
        assertEquals(1, detail.seats.getValue(SeatId(12)).visitCount)
        assertEquals(SeatId(12), detail.favouriteSeat)
    }

    @Test
    fun markCoversSeatIdsReferencedByData() = runTest {
        // Defensive: data referencing an id above the stored mark (e.g. from an import)
        // raises the mark too.
        val hallId = halls.createHall(cinemas.createCinema("Kino"), "Saal 1")
        db.visitDao().insert(Visit(hallId = hallId, date = date, seatId = SeatId(40)).toEntity())
        val hall = halls.getHall(hallId)!!
        assertEquals(41, hall.nextSeatId)
        assertEquals(41, halls.observeHallDetail(hallId).first()!!.hall.nextSeatId)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { halls.updateHall(hall.copy(layout = SeatLayout.create(5, 8, firstSeatId = 1))) }
        }
        halls.updateHall(hall.copy(layout = hall.startLayoutEdit().newGrid(5, 8).layout))
        assertTrue(halls.getHall(hallId)!!.layout!!.seatIds.all { it.value >= 41 })
    }
}
