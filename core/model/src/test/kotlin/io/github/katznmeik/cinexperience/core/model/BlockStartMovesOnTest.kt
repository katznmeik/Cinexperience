package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * When a block with an explicit start loses the seat the start is keyed on (its
 * first numbered seat in numbering order), the start moves on to the next
 * numbered seat with the number that seat had, so the remaining seats keep their
 * numbers (spec §4.3: they must match Yorck's booking).
 */
class BlockStartMovesOnTest {

    private fun SeatLayout.texts(row: Int = 0): List<String> {
        val labels = labels()
        return rows[row].seatIds.map { labels.labelOf(it)!!.seat }
    }

    private fun SeatLayout.starts(row: Int = 0): List<Int?> = rows[row].blockStartNumbers

    private fun SeatLayout.withStarts(vararg starts: Int?): SeatLayout =
        updateRow(0) { it.copy(blockStartNumbers = starts.toList()) }

    private fun SeatLayout.rightToLeft(): SeatLayout =
        updateRow(0) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }

    /** Rollberg Saal 5 row 5 style: `.......SSSSS`, right to left from 2 (6 5 4 3 2). */
    private val rollberg = layoutOf(".......SSSSS").rightToLeft().withStarts(2)

    /** Cinema Paris row 7: 1–8 | 12–19. */
    private val paris = layoutOf("SSSSSSSS|||SSSSSSSS").withStarts(null, 12)

    @Test
    fun removingTheLastColumnKeepsTheOtherSeatsNumbers() {
        assertEquals(listOf("6", "5", "4", "3", "2"), rollberg.texts())
        val narrower = rollberg.removeColumn(11)
        assertEquals(listOf("6", "5", "4", "3"), narrower.texts())
        assertEquals(listOf(3), narrower.starts())
        assertEquals(listOf("6", "5", "4"), narrower.removeColumn(10).texts())
        assertEquals(listOf("6", "5", "4"), rollberg.resize(1, 10).texts())
    }

    @Test
    fun anAislePaintedOverTheFirstSeatKeepsTheOtherSeatsNumbers() {
        val aisle = rollberg.withCellType(0, 11, CellType.AISLE)
        assertEquals(listOf("6", "5", "4", "3"), aisle.texts())

        // A two-place row (Kant Kino Saal 3 style): the other place keeps its number.
        val sofa = layoutOf("FF...").rightToLeft().withStarts(13)
        assertEquals(listOf("14", "13"), sofa.texts())
        assertEquals(listOf("14"), sofa.withCellType(0, 1, CellType.GAP).texts())
        assertEquals(emptyList(), sofa.withCellType(0, 1, CellType.GAP).withCellType(0, 0, CellType.GAP).starts())
    }

    @Test
    fun parisWithoutSeat12Reads13To19AndTheSeatPaintedBackRestores12() {
        val without12 = paris.withCellType(0, 11, CellType.GAP)
        assertEquals((1..8).map { "$it" } + (13..19).map { "$it" }, without12.texts())
        assertEquals(listOf(null, 13), without12.starts())

        // In a session the original seat gets its own start back, in either order.
        val session = LayoutEditSession.start(paris, hallNextSeatId = 17)
        val away = session.setCellType(0, 11, CellType.GAP).setCellType(0, 12, CellType.AISLE)
        assertEquals((1..8).map { "$it" } + (14..19).map { "$it" }, away.layout!!.texts())
        listOf(
            away.setCellType(0, 11, CellType.SEAT).setCellType(0, 12, CellType.SEAT),
            away.setCellType(0, 12, CellType.SEAT).setCellType(0, 11, CellType.SEAT),
        ).forEach { back ->
            assertEquals((1..8).map { "$it" } + (12..19).map { "$it" }, back.layout!!.texts())
            assertEquals(paris.rows, back.layout!!.rows)
            assertFalse(back.hasChanges)
        }
    }

    @Test
    fun fixedLabelsAndOtherBlocksAreNotAffected() {
        // Right to left with an "R" first in numbering order: the start moves past it to the next number.
        val withR = layoutOf("SSSW").rightToLeft().withFixedLabel(0, 3, "R").withStarts(3) // 5 4 3 R
        assertEquals(listOf("5", "4", "3", "R"), withR.texts())
        val gone = withR.withCellType(0, 2, CellType.GAP)
        assertEquals(listOf("5", "4", "R"), gone.texts())
        assertEquals(listOf(4), gone.starts())

        // An "R" alone left in the block keeps nothing; the next block is untouched.
        val pair = layoutOf("WS|SS").withFixedLabel(0, 0, "R").withStarts(5, 10) // R 5 | 10 11
        val rOnly = pair.withCellType(0, 1, CellType.GAP)
        assertEquals(listOf("R", "10", "11"), rOnly.texts())
        assertEquals(listOf(null, 10), rOnly.starts())

        // Restart per block: the remaining seats keep their numbers too.
        val restart = layoutOf("SSS|SSS").updateRow(0) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }
            .withStarts(null, 20)
        assertEquals(listOf("1", "2", "3", "21", "22"), restart.withCellType(0, 4, CellType.GAP).texts())
    }
}
