package io.github.katznmeik.cinexperience.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which visits are upcoming and their order (spec §4.4, §6.1, §6.8). */
class UpcomingVisitsTest {

    /** Thursday 15 Oct 2026, 10:00 in Berlin (summer time). */
    private val now = Instant.parse("2026-10-15T08:00:00Z")
    private val today = LocalDate.of(2026, 10, 15)

    private fun item(id: Long, date: LocalDate, startTime: LocalTime?) =
        VisitListItem(Visit(id = id, hallId = 1, date = date, startTime = startTime), 1, "delphi LUX", "Saal 4", seatLabel = null)

    private fun ids(items: List<VisitListItem>) = items.map { it.visit.id }

    @Test
    fun soonestFirstByDateThenStartTime() {
        val items = listOf(
            item(1, today.plusDays(2), LocalTime.of(17, 0)),
            item(2, today, LocalTime.of(20, 15)),
            item(3, today.plusDays(1), LocalTime.of(11, 0)),
            item(4, today, LocalTime.of(10, 30)),
        )
        assertEquals(listOf(4L, 2, 3, 1), ids(UpcomingVisits.of(items, now)))
    }

    @Test
    fun pastAndStartedVisitsAreNotUpcoming() {
        val items = listOf(
            item(1, today, LocalTime.of(10, 0)), // starts right now: not upcoming any more
            item(2, today, LocalTime.of(9, 45)), // started
            item(3, today.minusDays(1), LocalTime.of(20, 0)),
            item(4, today, null), // today without a start time
            item(5, today, LocalTime.of(10, 1)),
        )
        assertEquals(listOf(5L), ids(UpcomingVisits.of(items, now)))
    }

    @Test
    fun aVisitWithoutAStartTimeComesAfterTheTimedVisitsOfItsDay() {
        val tomorrow = today.plusDays(1)
        val items = listOf(
            item(1, tomorrow, null),
            item(2, tomorrow, LocalTime.of(22, 30)),
            item(3, tomorrow, LocalTime.of(9, 0)),
            item(4, today.plusDays(2), LocalTime.of(0, 15)),
        )
        assertEquals(listOf(3L, 2, 1, 4), ids(UpcomingVisits.of(items, now)))
    }

    @Test
    fun equalShowingsKeepTheOrderTheyWereSavedIn() {
        val evening = LocalTime.of(20, 15)
        val items = listOf(item(9, today, evening), item(3, today, evening), item(5, today, evening))
        assertEquals(listOf(3L, 5, 9), ids(UpcomingVisits.of(items, now)))
        val noTime = listOf(item(8, today.plusDays(1), null), item(2, today.plusDays(1), null))
        assertEquals(listOf(2L, 8), ids(UpcomingVisits.of(noTime, now)))
    }

    @Test
    fun theComparatorSortsVisits() {
        val visits = listOf(
            Visit(id = 2, hallId = 1, date = today, startTime = null),
            Visit(id = 1, hallId = 1, date = today, startTime = LocalTime.of(23, 0)),
            Visit(id = 3, hallId = 1, date = today.minusDays(1), startTime = LocalTime.of(23, 30)),
        )
        assertEquals(listOf(3L, 1, 2), visits.sortedWith(UpcomingVisits.SOONEST_FIRST).map { it.id })
    }
}
