package io.github.katznmeik.cinexperience.reminders

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The coordinator's debounce ([leadingDebounce], one-second window): the first value at
 * once, later ones within the window once they have settled, and no value lost when it
 * arrives at the moment a window ends.
 */
class LeadingDebounceTest {

    private val window = 1.seconds

    /** (virtual time, value) of everything [source] gives through the debounce within 10 s; the source stays open. */
    private fun TestScope.emitted(source: Flow<Int?>): List<Pair<Long, Int?>> {
        val out = ArrayList<Pair<Long, Int?>>()
        backgroundScope.launch { source.leadingDebounce(window).collect { out += testScheduler.currentTime to it } }
        advanceTimeBy(10_000)
        runCurrent()
        return out
    }

    @Test
    fun theFirstValueAtOnceTheLastOfQuickOnesOnceTheyHaveSettled() = runTest {
        val out = emitted(
            flow {
                emit(1)
                delay(300)
                emit(2)
                delay(300)
                emit(3)
                awaitCancellation()
            },
        )
        assertEquals(listOf(0L to 1, 1_600L to 3), out)
    }

    @Test
    fun aValueExactlyOneWindowAfterTheFirstComesOut() = runTest {
        // It arrives as the window after the first value ends.
        val out = emitted(
            flow {
                emit(1)
                delay(1_000)
                emit(2)
                awaitCancellation()
            },
        )
        assertEquals(listOf(1, 2), out.map { it.second })
        assertEquals(0L, out.first().first)
    }

    @Test
    fun aValueExactlyOneWindowAfterAWaitingOneComesOut() = runTest {
        // 2 waits for its window to pass; 3 arrives as that window ends.
        val out = emitted(
            flow {
                emit(1)
                delay(500)
                emit(2)
                delay(1_000)
                emit(3)
                awaitCancellation()
            },
        )
        assertEquals(1, out.first().second)
        assertEquals(3, out.last().second)
    }

    @Test
    fun nullIsAValueAndTheEndGivesTheWaitingOne() = runTest {
        // null: a backup import runs. A source that ends gives its waiting value at once.
        val out = emitted(
            flow {
                emit(null)
                delay(100)
                emit(4)
            },
        )
        assertEquals(listOf(0L to null, 100L to 4), out)
    }
}
