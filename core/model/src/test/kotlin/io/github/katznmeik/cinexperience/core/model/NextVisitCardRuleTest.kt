package io.github.katznmeik.cinexperience.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Next visit card's own rule (spec §6.1, owner decision of 18 Oct 2026): a
 * showing stays 30 minutes after its start, a visit without a start time all of its
 * day. Upcoming visits (§4.4) stay as they are.
 */
class NextVisitCardRuleTest {

    /** Thursday 15 Oct 2026, 20:30 in Berlin (summer time). */
    private val now = Instant.parse("2026-10-15T18:30:00Z")
    private val today = LocalDate.of(2026, 10, 15)

    private fun item(id: Long, date: LocalDate, startTime: LocalTime?) =
        VisitListItem(Visit(id = id, hallId = 1, date = date, startTime = startTime), 1, "delphi LUX", "Saal 4", seatLabel = null)

    private fun card(items: List<VisitListItem>, at: Instant = now) = UpcomingVisits.forNextVisitCard(items, at).map { it.visit.id }

    @Test
    fun aShowingStaysThirtyMinutesAfterItsStart() {
        val items = listOf(
            item(1, today, LocalTime.of(20, 0)), // started 30 min ago: gone
            item(2, today, LocalTime.of(20, 1)), // started 29 min ago: stays
            item(3, today, LocalTime.of(20, 15)),
            item(4, today, LocalTime.of(21, 0)),
        )
        assertEquals(listOf(2L, 3, 4), card(items))
        // Upcoming visits (§4.4) are only the one still ahead.
        assertEquals(listOf(4L), UpcomingVisits.of(items, now).map { it.visit.id })
    }

    @Test
    fun todaysVisitWithoutAStartTimeStaysUntilMidnightAfterTheTimedOnes() {
        val items = listOf(item(1, today, null), item(2, today, LocalTime.of(20, 15)), item(3, today.minusDays(1), null))
        assertEquals(listOf(2L, 1), card(items))
        assertEquals(listOf(1L), card(items, Instant.parse("2026-10-15T21:59:59Z"))) // 23:59:59 in Berlin
        assertEquals(emptyList(), card(items, Instant.parse("2026-10-15T22:00:00Z"))) // midnight
        assertTrue(UpcomingVisits.of(items, now).isEmpty())
    }

    @Test
    fun laterVisitsInTheSoonestFirstOrder() {
        val items = listOf(
            item(5, today.plusDays(1), null),
            item(4, today.plusDays(1), LocalTime.of(9, 0)),
            item(6, today, LocalTime.of(20, 10)),
        )
        assertEquals(listOf(6L, 4, 5), card(items))
    }

    @Test
    fun startedMeansTheStartTimeHasCome() {
        val visit = Visit(hallId = 1, date = today, startTime = LocalTime.of(20, 30))
        assertTrue(visit.hasStarted(now))
        assertFalse(visit.hasStarted(now.minusSeconds(1)))
        assertFalse(visit.copy(startTime = null).hasStarted(now))
    }
}
