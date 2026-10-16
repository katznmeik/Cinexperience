package io.github.katznmeik.cinexperience.core.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The app's date and time texts (English, 24 h), shared by the Program, Now showing, visits and backup texts. */
class DatesTest {

    private val today = LocalDate.of(2026, 10, 15) // a Thursday

    @Test
    fun dayHeaderNamesTodayAndTomorrow() {
        assertEquals("Today · Thursday 15 Oct", dayHeaderText(today, today))
        assertEquals("Tomorrow · Friday 16 Oct", dayHeaderText(today.plusDays(1), today))
        assertEquals("Saturday 17 Oct", dayHeaderText(today.plusDays(2), today))
        assertEquals("Wednesday 14 Oct", dayHeaderText(today.minusDays(1), today))
    }

    @Test
    fun dayHeaderAcrossMonthAndYearEnd() {
        val newYearsEve = LocalDate.of(2026, 12, 31)
        assertEquals("Today · Thursday 31 Dec", dayHeaderText(newYearsEve, newYearsEve))
        assertEquals("Tomorrow · Friday 1 Jan", dayHeaderText(LocalDate.of(2027, 1, 1), newYearsEve))
    }

    @Test
    fun dayTexts() {
        assertEquals("Thursday 15 Oct", dayText(today))
        assertEquals("Sat 17 Oct", shortDayText(LocalDate.of(2026, 10, 17)))
        assertEquals("5 Oct", dayMonthText(LocalDate.of(2026, 10, 5)))
        assertEquals("12 Sep 2026", dateText(LocalDate.of(2026, 9, 12)))
    }

    @Test
    fun timesAre24HourWithLeadingZeros() {
        assertEquals("16:30", timeText(LocalTime.of(16, 30)))
        assertEquals("09:05", timeText(LocalTime.of(9, 5)))
        assertEquals("00:00", timeText(LocalTime.MIDNIGHT))
        assertEquals("23:59", timeText(LocalTime.of(23, 59, 59)))
        assertEquals("18:28", timeText(LocalDateTime.of(2026, 10, 15, 18, 28)))
    }

    @Test
    fun dateTimeInTheGivenZone() {
        val instant = Instant.parse("2026-10-03T12:05:00Z")
        assertEquals("3 Oct 2026, 14:05", dateTimeText(instant, ZoneOffset.ofHours(2)))
        assertEquals("3 Oct 2026, 14:05", dateTimeText(instant, ZoneId.of("Europe/Berlin")))
        // Winter time in Berlin: one hour ahead of UTC; the date can change.
        assertEquals("1 Jan 2027, 00:30", dateTimeText(Instant.parse("2026-12-31T23:30:00Z"), ZoneId.of("Europe/Berlin")))
    }
}
