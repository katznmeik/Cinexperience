package io.github.katznmeik.cinexperience.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

/** The build pins the unit tests' time zone (build-logic), so dates don't depend on the machine. */
class UnitTestTimeZoneTest {

    @Test
    fun unitTestsRunInUtc() {
        assertEquals("UTC", TimeZone.getDefault().id)
        assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault())
    }
}
