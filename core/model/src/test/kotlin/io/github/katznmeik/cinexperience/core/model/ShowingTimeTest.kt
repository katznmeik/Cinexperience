package io.github.katznmeik.cinexperience.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A visit's start, upcoming visits and the QR code's expiry (spec §4.4, §4.7), all in Berlin time. */
class ShowingTimeTest {

    private val date = LocalDate.of(2026, 10, 17)
    private val evening = LocalTime.of(20, 15)
    private fun visit(date: LocalDate = this.date, startTime: LocalTime? = evening) = Visit(hallId = 1, date = date, startTime = startTime)
    private fun at(text: String): Instant = Instant.parse(text)

    @Test
    fun theStartIsBerlinClockTime() {
        // Summer time (UTC+2) until 25 Oct 2026, then winter time (UTC+1).
        assertEquals(at("2026-10-17T18:15:00Z"), visit().startInstant)
        assertEquals(at("2026-12-01T19:15:00Z"), visit(LocalDate.of(2026, 12, 1)).startInstant)
        assertNull(visit(startTime = null).startInstant)
        assertEquals(visit().startInstant, ShowingTime.startOf(date, evening))
    }

    @Test
    fun clockChangesFollowTheJavaTimeRule() {
        // Spring gap: 02:30 doesn't exist on 29 Mar 2026 and becomes 03:30 summer time.
        assertEquals(at("2026-03-29T01:30:00Z"), ShowingTime.startOf(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30)))
        // Autumn overlap: 02:30 exists twice on 25 Oct 2026; the earlier one (summer time) counts.
        assertEquals(at("2026-10-25T00:30:00Z"), ShowingTime.startOf(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30)))
    }

    @Test
    fun aVisitWithAStartTimeIsUpcomingUntilItStarts() {
        val start = visit().startInstant!!
        assertTrue(visit().isUpcoming(start.minusSeconds(1)))
        assertFalse(visit().isUpcoming(start))
        assertFalse(visit().isUpcoming(start.plusSeconds(1)))
        assertTrue(visit(date.plusDays(30)).isUpcoming(start))
        assertFalse(visit(date.minusDays(1)).isUpcoming(start.minusSeconds(3600)))
    }

    @Test
    fun aVisitWithoutAStartTimeIsUpcomingFromTheDayBefore() {
        val noTime = visit(startTime = null)
        // Berlin's 17 Oct ends at 22:00 UTC (summer time): until then, "today" is the 17th.
        assertTrue(noTime.isUpcoming(at("2026-10-16T21:59:59Z")))
        assertFalse(noTime.isUpcoming(at("2026-10-16T22:00:00Z"))) // already the 17th in Berlin, still the 16th in UTC
        assertFalse(noTime.isUpcoming(at("2026-10-17T12:00:00Z")))
        assertFalse(noTime.isUpcoming(at("2026-10-18T12:00:00Z")))
        assertEquals(LocalDate.of(2026, 10, 17), ShowingTime.today(at("2026-10-16T22:00:00Z")))
    }

    @Test
    fun qrModulesExpireSevenDaysAfterTheStart() {
        // 17 Oct 20:15 summer time + 7 Berlin days = 24 Oct 20:15, still summer time.
        assertEquals(at("2026-10-24T18:15:00Z"), visit().qrModulesExpireAt)
        // Across the clock change: 20 Oct 20:15 (UTC+2) → 27 Oct 20:15 (UTC+1), the same clock time.
        assertEquals(at("2026-10-27T19:15:00Z"), visit(LocalDate.of(2026, 10, 20)).qrModulesExpireAt)
        val expiry = visit().qrModulesExpireAt
        assertFalse(ShowingTime.qrModulesExpired(date, evening, expiry.minusSeconds(1)))
        assertTrue(ShowingTime.qrModulesExpired(date, evening, expiry))
        assertTrue(ShowingTime.qrModulesExpired(date, evening, expiry.plusSeconds(1)))
    }

    @Test
    fun withoutAStartTimeQrModulesExpireSevenDaysAfterTheEndOfTheDay() {
        // The 17th ends at midnight Berlin time; 7 days later is 25 Oct 00:00, still summer time (UTC+2).
        assertEquals(at("2026-10-24T22:00:00Z"), ShowingTime.qrModulesExpireAt(date, null))
        assertEquals(at("2026-10-24T22:00:00Z"), visit(startTime = null).qrModulesExpireAt)
        assertFalse(ShowingTime.qrModulesExpired(date, null, at("2026-10-24T21:59:59Z")))
        assertTrue(ShowingTime.qrModulesExpired(date, null, at("2026-10-24T22:00:00Z")))
    }

    @Test
    fun aStartTimeIsToTheMinute() {
        assertEquals(LocalTime.of(0, 0), visit(startTime = LocalTime.MIDNIGHT).startTime)
        assertEquals(LocalTime.of(23, 59), visit(startTime = LocalTime.of(23, 59)).startTime)
        assertFailsWith<IllegalArgumentException> { visit(startTime = LocalTime.of(20, 15, 1)) }
        assertFailsWith<IllegalArgumentException> { visit(startTime = LocalTime.of(20, 15, 0, 1)) }
        assertTrue(ShowingTime.isWholeMinute(evening))
        assertFalse(ShowingTime.isWholeMinute(LocalTime.MAX))
    }
}
