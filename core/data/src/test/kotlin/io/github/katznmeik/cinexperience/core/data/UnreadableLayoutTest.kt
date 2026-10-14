package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.db.toEntity
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A layout this app cannot read must not break any Flow, nor be lost by unrelated edits. */
internal class UnreadableLayoutTest : DatabaseTest() {

    private val futureJson = """{"formatVersion":99,"rows":[{"cells":"SS"}]}"""

    private suspend fun hallWithFutureLayout(): Long {
        val cinemaId = cinemas.createCinema("Kino")
        val good = halls.createHall(cinemaId, "Saal 2")
        halls.updateHall(halls.getHall(good)!!.copy(layout = layoutOf("SS")))
        val hallId = halls.createHall(cinemaId, "Saal 1")
        db.hallDao().update(db.hallDao().get(hallId)!!.copy(layoutJson = futureJson, nextSeatId = 3))
        db.visitDao().insert(Visit(hallId = hallId, date = LocalDate.of(2026, 1, 1), seatId = SeatId(2)).toEntity())
        visits.save(Visit(hallId = good, date = LocalDate.of(2026, 1, 2), seatId = SeatId(1)))
        return hallId
    }

    @Test
    fun flowsStillEmitWithTheHallFlaggedAndWithoutLayout() = runTest {
        val hallId = hallWithFutureLayout()
        val cinemaId = halls.getHall(hallId)!!.cinemaId

        assertEquals(2, cinemas.observeCinemas().first().single().visitCount)

        val detail = cinemas.observeCinemaDetail(cinemaId).first()!!
        val broken = detail.halls.single { it.hall.id == hallId }.hall
        assertTrue(broken.layoutUnreadable)
        assertNull(broken.layout)
        assertFalse(detail.halls.single { it.hall.id != hallId }.hall.layoutUnreadable)

        val all = visits.observeAllVisits().first()
        assertEquals(2, all.size)
        assertNull(all.single { it.visit.hallId == hallId }.seatLabel)
        assertEquals("1", all.single { it.visit.hallId != hallId }.seatLabel!!.row)

        val hallDetail = halls.observeHallDetail(hallId).first()!!
        assertTrue(hallDetail.hall.layoutUnreadable)
        assertTrue(hallDetail.seats.isEmpty())
        assertEquals(1, hallDetail.visits.size)
    }

    @Test
    fun editingOtherFieldsKeepsTheStoredLayout() = runTest {
        val hallId = hallWithFutureLayout()
        val hall = halls.getHall(hallId)!!
        halls.updateHall(hall.copy(name = "Renamed", notes = "note"))
        assertEquals(futureJson, db.hallDao().get(hallId)!!.layoutJson)
        assertEquals("Renamed", halls.getHall(hallId)!!.name)
    }

    @Test
    fun drawingANewLayoutReplacesItWithFreshIds() = runTest {
        val hallId = hallWithFutureLayout()
        val hall = halls.getHall(hallId)!!
        assertEquals(3, hall.nextSeatId)
        val layout = hall.startLayoutEdit().newGrid(1, 3).layout!!
        halls.updateHall(hall.copy(layout = layout))
        val saved = halls.getHall(hallId)!!
        assertFalse(saved.layoutUnreadable)
        assertEquals(layout, saved.layout)
        assertTrue(layout.seatIds.all { it.value >= 3 })
    }
}
