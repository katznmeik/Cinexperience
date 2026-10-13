package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeatLabelsTest {

    /** Seat numbers of one row, left to right. */
    private fun SeatLayout.numbers(row: Int): List<Int> {
        val labels = labels()
        return rows[row].seatIds.map { labels.labelOf(it)!!.number }
    }

    private fun SeatLayout.blocks(row: Int): List<Int> {
        val labels = labels()
        return rows[row].seatIds.map { labels.seats.getValue(it).block }
    }

    @Test
    fun simpleGridIsNumberedFromOne() {
        val layout = layoutOf("SSS", "SSS")
        val labels = layout.labels()
        assertEquals(listOf("1", "2"), labels.rowLabels)
        assertEquals(SeatLabel("1", 1), labels.labelOf(SeatId(1)))
        assertEquals(SeatLabel("2", 3), labels.labelOf(SeatId(6)))
        assertEquals("Row 2, Seat 3", labels.labelOf(SeatId(6))!!.displayText)
        assertEquals(6, labels.seats.size)
    }

    @Test
    fun gapsAreSkippedAndDoNotSplitBlocks() {
        val layout = layoutOf("..SS.SS")
        assertEquals(listOf(1, 2, 3, 4), layout.numbers(0))
        assertEquals(listOf(0, 0, 0, 0), layout.blocks(0))
        assertEquals(CellPosition(0, 2), layout.labels().seats.getValue(SeatId(1)).position)
    }

    @Test
    fun rowsWithoutSeatsGetNoLabelAndDoNotCount() {
        val layout = layoutOf("SS", "||", "SS", "..")
        val labels = layout.labels()
        assertEquals(listOf("1", null, "2", null), labels.rowLabels)
        assertEquals("2", labels.labelOf(SeatId(3))!!.row)
    }

    @Test
    fun customRowLabelsWinAndAutoCounterKeepsPosition() {
        val layout = layoutOf("SS", "SS", "SS")
            .updateRow(0) { it.copy(label = "A") }
        assertEquals(listOf("A", "2", "3"), layout.labels().rowLabels)
    }

    @Test
    fun customLabelOnSeatlessRowIsKept() {
        val layout = layoutOf("||", "SS").updateRow(0) { it.copy(label = "Aisle") }
        assertEquals(listOf("Aisle", "1"), layout.labels().rowLabels)
    }

    @Test
    fun splitRowContinuousNumbering() {
        val layout = layoutOf("SSS|SSSS|SS")
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), layout.numbers(0))
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 2, 2), layout.blocks(0))
    }

    @Test
    fun splitRowRestartPerBlock() {
        val layout = layoutOf("SSS|SSSS|SS")
            .updateRow(0) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }
        assertEquals(listOf(1, 2, 3, 1, 2, 3, 4, 1, 2), layout.numbers(0))
    }

    @Test
    fun restartWithCustomStartPerBlock() {
        val layout = layoutOf("SS|SS|SS").updateRow(0) {
            it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK, blockStartNumbers = listOf(null, 101, 201))
        }
        assertEquals(listOf(1, 2, 101, 102, 201, 202), layout.numbers(0))
    }

    @Test
    fun continuousWithCustomStartResetsCounterForFollowingBlocks() {
        val layout = layoutOf("SS|SS|SS").updateRow(0) {
            it.copy(blockStartNumbers = listOf(5, 20))
        }
        // Block 0 from 5, block 1 from 20, block 2 continues from block 1.
        assertEquals(listOf(5, 6, 20, 21, 22, 23), layout.numbers(0))
    }

    @Test
    fun leadingTrailingAndDoubleAislesDoNotCreateEmptyBlocks() {
        val layout = layoutOf("|SS||SS|").updateRow(0) {
            it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK)
        }
        assertEquals(listOf(0, 0, 1, 1), layout.blocks(0))
        assertEquals(listOf(1, 2, 1, 2), layout.numbers(0))
    }

    @Test
    fun aisleSeparatedByGapStillSplits() {
        val layout = layoutOf("SS.|.SS")
        assertEquals(listOf(0, 0, 1, 1), layout.blocks(0))
    }

    @Test
    fun staggeredRowsKeepTheirNumbering() {
        val layout = layoutOf("SSSS", "SSS.")
            .updateRow(1) { it.copy(halfSeatOffset = true) }
        assertEquals(listOf(1, 2, 3), layout.numbers(1))
    }

    @Test
    fun labelsSurviveInsertingRowInFrontWithShiftedAutoNumbers() {
        val before = layoutOf("SS", "SS")
        val after = before.insertRow(0)
        // Same physical seat (id 3), now in auto row 3 instead of 2.
        assertEquals(SeatLabel("2", 1), before.labels().labelOf(SeatId(3)))
        assertEquals(SeatLabel("3", 1), after.labels().labelOf(SeatId(3)))
    }

    @Test
    fun unknownSeatHasNoLabel() {
        assertNull(layoutOf("S").labels().labelOf(SeatId(99)))
    }
}
