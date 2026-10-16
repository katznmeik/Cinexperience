package io.github.katznmeik.cinexperience.core.ui.flow

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowsTest {

    @Test
    fun minuteTicksEmitNowThenOnceAMinute() = runTest {
        val times = minuteTicks().take(3).map { testScheduler.currentTime }.toList()
        assertEquals(listOf(0L, 60_000L, 120_000L), times)
    }
}
