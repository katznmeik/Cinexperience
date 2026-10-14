package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Test

/** The screenshot layouts number their seats as their docs say. */
class SampleLayoutsTest {

    private val layout = SampleLayouts.yorckNumbering
    private val labels = layout.labels()

    private fun texts(row: Int): List<String> = layout.rows[row].seatIds.map { labels.labelOf(it)!!.seat }

    @Test
    fun yorckNumberingRows() {
        assertEquals((1..11).map { "$it" }, texts(0))
        assertEquals((13 downTo 1).map { "$it" }, texts(1))
        assertEquals((3..15).map { "$it" }, texts(2))
        assertEquals((13 downTo 1).map { "$it" }, texts(3))
        assertEquals(listOf(1, 2, 3, 1, 2, 3, 4, 5, 6, 7, 1, 2, 3).map { "$it" }, texts(4))
        assertEquals((14 downTo 1).map { "$it" } + "R", texts(5))
        assertEquals(listOf("R") + (1..14).map { "$it" }, texts(6))
        assertEquals(SeatLabel("6", "R", SeatKind.WHEELCHAIR), labels.labelOf(SampleLayouts.yorckRowSixR))
        assertEquals(SeatLabel("7", 14, SeatKind.COMPANION), labels.labelOf(layout.rows[6].seatIds.last()))
    }
}
