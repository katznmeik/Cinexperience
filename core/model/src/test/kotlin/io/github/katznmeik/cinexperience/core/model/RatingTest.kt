package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RatingTest {

    @Test
    fun acceptsValuesOneToFive() {
        for (v in 1..5) {
            assertEquals(v, Rating(v).value)
        }
    }

    @Test
    fun rejectsValuesOutsideRange() {
        assertFailsWith<IllegalArgumentException> { Rating(0) }
        assertFailsWith<IllegalArgumentException> { Rating(6) }
        assertFailsWith<IllegalArgumentException> { Rating(-1) }
    }

    @Test
    fun ofOrNullTreatsMissingAndOutOfRangeAsUnrated() {
        assertNull(Rating.ofOrNull(null))
        assertNull(Rating.ofOrNull(0))
        assertNull(Rating.ofOrNull(6))
        assertEquals(Rating(3), Rating.ofOrNull(3))
    }
}
