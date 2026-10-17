package io.github.katznmeik.cinexperience.core.ui.flow

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class FlowsTest {

    @Test
    fun minuteTicksEmitNowThenOnceAMinute() = runTest {
        val times = minuteTicks().take(3).map { testScheduler.currentTime }.toList()
        assertEquals(listOf(0L, 60_000L, 120_000L), times)
    }

    @Test
    fun asResultWrapsEveryValue() = runTest {
        assertEquals(listOf(Result.success(1), Result.success(2)), flowOf(1, 2).asResult().toList())
    }

    @Test
    fun asResultEndsAFailedFlowWithItsFailure() = runTest {
        val error = IOException("disk")
        val results = flow {
            emit(1)
            throw error
        }.asResult().toList()

        assertEquals(2, results.size)
        assertEquals(Result.success(1), results[0])
        assertSame(error, results[1].exceptionOrNull())
    }

    @Test
    fun asResultAlsoCatchesAFailureOfAnEarlierStep() = runTest {
        val results = flowOf("1", "x").map { it.toInt() }.asResult().toList()

        assertEquals(Result.success(1), results[0])
        assertEquals(NumberFormatException::class.java, results[1].exceptionOrNull()?.javaClass)
    }
}
