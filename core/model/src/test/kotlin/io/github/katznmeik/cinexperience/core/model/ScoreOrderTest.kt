package io.github.katznmeik.cinexperience.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreOrderTest {

    /** mean[2.0, 2.0, 2.6]: mathematically 2.2, computed as 2.1999999999999997. */
    private val noisy = listOf(2.0, 2.0, 2.6).average()

    @Test
    fun mathematicallyEqualScoresTie() {
        assertNotEquals(2.2, noisy, 0.0)
        assertEquals("2.2", formatScore(noisy))
        assertEquals(0, ScoreOrder.compare(noisy, 2.2))
        assertEquals(0, ScoreOrder.compare(2.2, noisy))
        // A stable sort keeps tied scores in their input order, either way round.
        assertEquals(listOf("a", "b"), listOf("a" to noisy, "b" to 2.2).sortedWith(compareBy(ScoreOrder) { it.second }).map { it.first })
        assertEquals(listOf("b", "a"), listOf("b" to 2.2, "a" to noisy).sortedWith(compareBy(ScoreOrder) { it.second }).map { it.first })
    }

    @Test
    fun highestFirstAndUnratedLast() {
        val sorted = listOf(null, 3.0, 4.5, null, 1.0, 4.4).sortedWith(ScoreOrder)
        assertEquals(listOf(4.5, 4.4, 3.0, 1.0, null, null), sorted)
        assertTrue(ScoreOrder.compare(1.0, null) < 0)
        assertTrue(ScoreOrder.compare(null, 5.0) > 0)
        assertEquals(0, ScoreOrder.compare(null, null))
        // Different scores never tie, however close (means of ratings differ by far more).
        assertTrue(ScoreOrder.compare(4.0 + 1e-6, 4.0) < 0)
    }
}
