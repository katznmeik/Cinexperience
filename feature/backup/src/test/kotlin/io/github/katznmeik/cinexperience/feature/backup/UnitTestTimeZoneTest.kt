package io.github.katznmeik.cinexperience.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * The build pins the unit tests' time zone (build-logic), Robolectric tests
 * included: Settings shows times in the phone's zone, so its texts and
 * screenshots are the same on every machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnitTestTimeZoneTest {

    @Test
    fun robolectricTestsRunInUtc() {
        assertEquals("UTC", TimeZone.getDefault().id)
        assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault())
        assertEquals("3 Oct 2026, 12:05", dateTimeText(Instant.parse("2026-10-03T12:05:00Z")))
    }
}
