package io.github.katznmeik.cinexperience.core.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The short showing text of the Next visit card and the upcoming visits (spec §6.1, §6.8). */
class ShowingDayTextTest {

    private val today = LocalDate.of(2026, 10, 15) // a Thursday
    private val evening = LocalTime.of(20, 15)

    @Test
    fun todayAndTomorrowByName() {
        assertEquals("Today · 20:15", showingDayText(today, evening, today))
        assertEquals("Tomorrow · 09:05", showingDayText(today.plusDays(1), LocalTime.of(9, 5), today))
    }

    @Test
    fun laterDaysWithTheirWeekday() {
        assertEquals("Sat 17 Oct · 20:15", showingDayText(today.plusDays(2), evening, today))
        assertEquals("Mon 30 Nov · 20:15", showingDayText(LocalDate.of(2026, 11, 30), evening, today))
    }

    @Test
    fun anotherYearIsNamed() {
        val newYearsEve = LocalDate.of(2026, 12, 31)
        assertEquals("Tomorrow · 20:15", showingDayText(LocalDate.of(2027, 1, 1), evening, newYearsEve))
        assertEquals("Sat 2 Jan 2027 · 20:15", showingDayText(LocalDate.of(2027, 1, 2), evening, newYearsEve))
    }

    @Test
    fun withoutAStartTimeOnlyTheDay() {
        assertEquals("Tomorrow", showingDayText(today.plusDays(1), null, today))
        assertEquals("Sat 17 Oct", showingDayText(today.plusDays(2), null, today))
        assertEquals("Today", showingDayText(today, null, today))
    }
}
