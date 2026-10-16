package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.db.VisitEntity
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.repository.RoomVisitRepository
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/**
 * All visits reads each hall's layout once, not once per visit: a hall with many
 * visits (or a padded layout) doesn't multiply the layout's JSON in memory. The
 * list itself (order, names, seat labels) is unchanged.
 */
internal class AllVisitsLayoutReadsTest : DatabaseTest() {

    private val decoded = mutableListOf<String>()
    private val counting by lazy {
        RoomVisitRepository(db, Dispatchers.Default, decodeLayout = { json -> decoded += json; LayoutJson.decodeOrNull(json) })
    }

    private val layoutA = layoutOf("SS|SS", "SSSSS").updateRow(1) { it.copy(label = "K") }

    private suspend fun hall(cinemaId: Long, name: String, layout: SeatLayout?): Long {
        val id = halls.createHall(cinemaId, name)
        if (layout != null) halls.updateHall(halls.getHall(id)!!.copy(layout = layout))
        return id
    }

    @Test
    fun eachHallsLayoutIsDecodedOnceAndTheListIsUnchanged() = runTest {
        val cinema = cinemas.createCinema("Kino")
        val hallA = hall(cinema, "A", layoutA)
        val hallB = hall(cinema, "B", layoutOf("SSS"))
        val noLayout = hall(cinema, "C", null)
        val unreadable = hall(cinema, "D", layoutOf("SS"))
        db.hallDao().update(db.hallDao().get(unreadable)!!.copy(layoutJson = "{\"formatVersion\":99,\"rows\":[]}"))

        val start = LocalDate.of(2026, 1, 1)
        repeat(200) { i -> visits.save(Visit(hallId = hallA, date = start.plusDays(i % 30L), seatId = SeatId(i % 8 + 1))) }
        visits.save(Visit(hallId = hallB, date = start, seatId = SeatId(2)))
        visits.save(Visit(hallId = hallB, date = start.plusDays(3)))
        visits.save(Visit(hallId = noLayout, date = start, seatId = SeatId(1)))
        visits.save(Visit(hallId = unreadable, date = start, seatId = SeatId(1)))

        val list = counting.observeAllVisits().first()

        // Once per hall with a layout (the unreadable one included). That each hall's
        // JSON is read only once is shown by theVisitsOfAHallShareOneReadOfItsLayout.
        assertEquals(3, decoded.size)
        assertEquals(204, list.size)
        // Newest first, ties by insertion order: the same order as before.
        val expectedOrder = db.backupDao().visits().sortedWith(compareByDescending<VisitEntity> { it.date }.thenByDescending { it.id })
        assertEquals(expectedOrder.map { it.id }, list.map { it.visit.id })
        // Labels from each hall's current layout: seat 8 of hall A is row K's last seat.
        val labelsA = layoutA.labels()
        for (item in list.filter { it.visit.hallId == hallA }) {
            assertEquals(labelsA.labelOf(item.visit.seatId!!), item.seatLabel)
        }
        assertEquals(SeatLabel("K", 4), list.first { it.visit.seatId == SeatId(8) }.seatLabel)
        assertEquals(SeatLabel("1", 3), list.first { it.visit.hallId == hallA && it.visit.seatId == SeatId(3) }.seatLabel)
        assertEquals(SeatLabel("1", 2), list.single { it.visit.hallId == hallB && it.visit.seatId != null }.seatLabel)
        assertNull(list.single { it.visit.hallId == noLayout }.seatLabel)
        assertNull(list.single { it.visit.hallId == unreadable }.seatLabel)
        assertEquals(listOf("A", "B", "C", "D"), list.map { it.hallName }.distinct().sorted())
        assertEquals(setOf("Kino"), list.map { it.cinemaName }.toSet())
    }

    @Test
    fun theVisitsOfAHallShareOneReadOfItsLayout() = runTest {
        val cinema = cinemas.createCinema("Kino")
        val hallA = hall(cinema, "A", layoutA)
        val hallB = hall(cinema, "B", layoutOf("SSS"))
        repeat(5) { visits.save(Visit(hallId = hallA, date = LocalDate.of(2026, 1, 1 + it))) }
        visits.save(Visit(hallId = hallB, date = LocalDate.of(2026, 2, 1)))

        val rows = db.visitDao().observeAllWithContext().first()
        val ofA = rows.filter { it.visit.hallId == hallA }
        assertEquals(5, ofA.size)
        for (row in ofA) assertSame(ofA.first().hallLayout, row.hallLayout)
        assertEquals(hallA, ofA.first().hallLayout!!.hallId)
        assertEquals(LayoutJson.encode(halls.getHall(hallA)!!.layout!!), ofA.first().hallLayout!!.layoutJson)
        assertEquals(hallB, rows.single { it.visit.hallId == hallB }.hallLayout!!.hallId)
    }

    @Test
    fun aRunningListIsRelabelledWhenTheLayoutChanges() = runBlocking {
        val cinema = cinemas.createCinema("Kino")
        val hallA = hall(cinema, "A", layoutA)
        visits.save(Visit(hallId = hallA, date = LocalDate.of(2026, 1, 1), seatId = SeatId(8)))
        // One collection across the change: the hall table is observed, so the flow re-emits.
        val labels = Channel<SeatLabel?>(Channel.UNLIMITED)
        val collecting = launch { visits.observeAllVisits().collect { labels.send(it.single().seatLabel) } }
        try {
            assertEquals(SeatLabel("K", 4), withTimeout(10_000) { labels.receive() })
            val hall = halls.getHall(hallA)!!
            halls.updateHall(hall.copy(layout = hall.layout!!.updateRow(1) { it.copy(label = "L") }))
            assertEquals(SeatLabel("L", 4), withTimeout(10_000) { labels.receive() })
        } finally {
            collecting.cancel()
        }
    }
}
