package io.github.katznmeik.cinexperience.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Material's date picker works in UTC midnight millis, whatever the device's time zone. */
class DatePickerDatesTest {

    @Test
    fun millisAreUtcMidnight() {
        assertEquals(0L, LocalDate.of(1970, 1, 1).toPickerMillis())
        assertEquals(86_400_000L, LocalDate.of(1970, 1, 2).toPickerMillis())
        assertEquals(1_791_590_400_000L, LocalDate.of(2026, 10, 10).toPickerMillis())
    }

    @Test
    fun roundTrips() {
        for (date in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25), LocalDate.of(2028, 2, 29), LocalDate.of(1969, 12, 31))) {
            assertEquals(date, date.toPickerMillis().toPickerDate())
        }
    }

    @Test
    fun anyTimeOfTheDayGivesThatDay() {
        val midnight = LocalDate.of(2026, 10, 10).toPickerMillis()
        assertEquals(LocalDate.of(2026, 10, 10), (midnight + 86_399_999L).toPickerDate())
        assertEquals(LocalDate.of(2026, 10, 9), (midnight - 1L).toPickerDate())
    }
}
