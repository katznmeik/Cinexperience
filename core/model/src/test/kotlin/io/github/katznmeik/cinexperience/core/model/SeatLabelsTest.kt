package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SeatLabelsTest {

    /** Seat numbers of one row, left to right. */
    private fun SeatLayout.numbers(row: Int): List<Int> {
        val labels = labels()
        return rows[row].seatIds.map { labels.seats.getValue(it).number!! }
    }

    /** Seat label texts of one row, left to right as drawn. */
    private fun SeatLayout.texts(row: Int = 0): List<String> {
        val labels = labels()
        return rows[row].seatIds.map { labels.labelOf(it)!!.seat }
    }

    private fun SeatLayout.rightToLeft(row: Int = 0): SeatLayout =
        updateRow(row) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }

    /** [layoutOf] with the seat at [column] of row 0 given the fixed [label]. */
    private fun SeatLayout.fixed(column: Int, label: String, row: Int = 0): SeatLayout = withFixedLabel(row, column, label)

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

    // ---- Numbering direction, every place numbered, fixed labels (spec §4.3, M8) ----

    @Test
    fun rightToLeftNumbersFromTheRightmostSeat() {
        val layout = layoutOf("SS.SS").rightToLeft()
        assertEquals(listOf("4", "3", "2", "1"), layout.texts())
        assertEquals(listOf(4, 3, 2, 1), layout.numbers(0))
        // The seats themselves are still listed left to right, in block 0.
        assertEquals(listOf(SeatId(1), SeatId(2), SeatId(3), SeatId(4)), layout.labels().seats.keys.toList())
        assertEquals(listOf(0, 0, 0, 0), layout.blocks(0))
    }

    @Test
    fun directionIsPerRow() {
        val layout = layoutOf("SSS", "SSS", "SSS").rightToLeft(1)
        assertEquals(listOf("1", "2", "3"), layout.texts(0))
        assertEquals(listOf("3", "2", "1"), layout.texts(1))
        assertEquals(listOf("1", "2", "3"), layout.texts(2))
    }

    @Test
    fun rightToLeftBlocksAreNumberedFromTheRightBlock() {
        val layout = layoutOf("SSS|SSSS").rightToLeft()
        // Block indices stay counted from the left.
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1), layout.blocks(0))
        assertEquals(listOf("7", "6", "5", "4", "3", "2", "1"), layout.texts())
        val restart = layout.updateRow(0) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }
        assertEquals(listOf("3", "2", "1", "4", "3", "2", "1"), restart.texts())
    }

    @Test
    fun blockStartNumbersAreIndexedFromTheLeftInBothDirections() {
        // The value is the number of the block's first seat in numbering order.
        val starts = listOf(10, null)
        val ltr = layoutOf("SSS|SSSS").updateRow(0) { it.copy(blockStartNumbers = starts) }
        assertEquals(listOf("10", "11", "12", "13", "14", "15", "16"), ltr.texts())
        val rtl = ltr.rightToLeft()
        assertEquals(listOf("12", "11", "10", "4", "3", "2", "1"), rtl.texts())
        // Right block from 20, left block continues.
        val right = layoutOf("SSS|SSSS").rightToLeft().updateRow(0) { it.copy(blockStartNumbers = listOf(null, 20)) }
        assertEquals(listOf("26", "25", "24", "23", "22", "21", "20"), right.texts())
    }

    @Test
    fun aRightToLeftRowStartingAtFour() {
        // Yorck rows often start at 2, 3 or 4.
        val layout = layoutOf("..SSSSSS").rightToLeft().updateRow(0) { it.copy(blockStartNumbers = listOf(4)) }
        assertEquals(listOf("9", "8", "7", "6", "5", "4"), layout.texts())
        assertEquals(listOf(9, 8, 7, 6, 5, 4), layout.numbers(0))
    }

    @Test
    fun numbersRunAcrossAislesAndGapsInStepsOfOne() {
        val layout = layoutOf("SS.S|S..SS|S").rightToLeft()
        assertEquals(listOf("7", "6", "5", "4", "3", "2", "1"), layout.texts())
    }

    @Test
    fun everyKindOfPlaceIsNumbered() {
        val layout = layoutOf("WSSWSC|FSW")
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), layout.texts())
        val labels = layout.labels()
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), labels.labelOf(SeatId(1)))
        assertEquals(SeatLabel("1", 6, SeatKind.COMPANION), labels.labelOf(SeatId(6)))
        assertEquals(SeatLabel("1", 7, SeatKind.SOFA), labels.labelOf(SeatId(7)))
        assertEquals(SeatLabel("1", 9, SeatKind.WHEELCHAIR), labels.labelOf(SeatId(9)))
        // A row of wheelchair spaces only is a numbered seat row.
        assertEquals(listOf("1", "2"), layoutOf("WW", "SS").labels().rowLabels)
        assertEquals(listOf("1", "2"), layoutOf("WW", "SS").texts(0))
    }

    @Test
    fun delphiLuxSaal1Row1() {
        // Yorck's ticketing data, left to right: W=1, W=2, seats 3–11, W=12, W=13.
        val layout = layoutOf("W.W|SSSSSSSSS|W.W").updateRow(0) { it.copy(label = "1") }
        assertEquals((1..13).map { "$it" }, layout.texts())
        val labels = layout.labels()
        assertEquals(SeatLabel("1", 2, SeatKind.WHEELCHAIR), labels.labelOf(SeatId(2)))
        assertEquals(SeatLabel("1", 3), labels.labelOf(SeatId(3)))
        assertEquals(SeatLabel("1", 13, SeatKind.WHEELCHAIR), labels.labelOf(SeatId(13)))
        assertEquals("Row 1, Seat 12, wheelchair space", labels.labelOf(SeatId(12))!!.description)
    }

    @Test
    fun kantKinoSaal4Row8RightToLeftWithAFixedR() {
        // Drawn left to right: 6 5 4 3 2 1 R; the R doesn't use up a number.
        val layout = layoutOf("SSSSSSW").rightToLeft().fixed(6, "R").updateRow(0) { it.copy(label = "8") }
        assertEquals(listOf("6", "5", "4", "3", "2", "1", "R"), layout.texts())
        val r = layout.labels().seats.getValue(SeatId(7))
        assertEquals(SeatLabel("8", "R", SeatKind.WHEELCHAIR), r.label)
        assertNull(r.number)
        assertEquals("Row 8, Seat R", r.label.displayText)
        assertEquals("Row 8, Seat R, wheelchair space", r.label.description)
    }

    @Test
    fun kantKinoSaal5Row1LeftToRightWithAFixedR() {
        val layout = layoutOf("WSSSSSS").fixed(0, "R")
        assertEquals(listOf("R", "1", "2", "3", "4", "5", "6"), layout.texts())
    }

    @Test
    fun fixedLabelsDoNotUseUpNumbersOrBlockStarts() {
        // A fixed label in the middle: the numbers close up around it.
        assertEquals(listOf("1", "2", "X", "3"), layoutOf("SSSS").fixed(2, "X").texts())
        // The block start applies to the block's first *numbered* seat.
        val start = layoutOf("SSS").fixed(0, "R").updateRow(0) { it.copy(blockStartNumbers = listOf(5)) }
        assertEquals(listOf("R", "5", "6"), start.texts())
        // A block of fixed labels only: the next block continues from the last number.
        val onlyFixed = layoutOf("SS|S|SS").fixed(3, "R")
        assertEquals(listOf("1", "2", "R", "3", "4"), onlyFixed.texts())
        // Labels needn't be unique: two "R" in one row are fine.
        assertEquals(listOf("R", "1", "R"), layoutOf("SSS").fixed(0, "R").fixed(2, "R").texts())
    }

    @Test
    fun fixedLabelsAreValidated() {
        val id = SeatId(1)
        assertEquals("R", LayoutCell.Seat(id, fixedLabel = "R").fixedLabel)
        assertEquals("R12a", LayoutCell.Seat(id, fixedLabel = "R12a").fixedLabel)
        assertEquals("Ü 1", LayoutCell.Seat(id, fixedLabel = "Ü 1").fixedLabel) // inner spaces and letters are fine
        // Blank, surrounding spaces, too long, ISO control characters (tab, newline, NUL, DEL, C1)
        // and format characters (zero-width space/joiner, bidi marks and overrides, soft hyphen, BOM).
        listOf(
            "", " ", "R ", " R", "ABCDE",
            "R\t1", "R\n", "\u0000", "R\u007F", "R\u0085",
            "R\u200B", "\u200DR", "R\u200E", "\u202ER", "R\u2066", "R\u00AD", "\uFEFFR",
        ).forEach { label ->
            assertFailsWith<IllegalArgumentException>(label) { LayoutCell.Seat(id, fixedLabel = label) }
        }
        assertFailsWith<IllegalArgumentException> { layoutOf("S.").withFixedLabel(0, 1, "R") }
        // Back to a number.
        val layout = layoutOf("SS").fixed(0, "R")
        assertEquals(listOf("1", "2"), layout.withFixedLabel(0, 0, null).texts())
    }

    @Test
    fun fixedLabelsMoveWithTheirSeat() {
        val layout = layoutOf("SSS").fixed(2, "R") // seat id 3
        val moved = layout.insertColumn(0).insertRow(0).withSeatKind(1, 3, SeatKind.WHEELCHAIR).withIdsShiftedBy(10)
        assertEquals(LayoutCell.Seat(SeatId(13), SeatKind.WHEELCHAIR, "R"), moved.cell(1, 3))
        assertEquals("R", moved.labels().labelOf(SeatId(13))!!.seat)
        // Painting the cell over drops it.
        assertEquals(LayoutCell.Seat(SeatId(4)), layout.withCellType(0, 2, CellType.GAP).withCellType(0, 2, CellType.SEAT).cell(0, 2))
    }

    @Test
    fun labelTexts() {
        assertEquals(SeatLabel("7", "10"), SeatLabel("7", 10))
        assertEquals("Row 7, Seat 10", SeatLabel("7", 10).displayText)
        assertEquals("Row C, Seat 4", SeatLabel("C", 4).displayText)
        assertEquals("Row R1, Seat 12, sofa", SeatLabel("R1", 12, SeatKind.SOFA).description)
        assertEquals("Row 6, Seat 12, companion seat", SeatLabel("6", 12, SeatKind.COMPANION).description)
        assertEquals("Row 7 · Seat 10", SeatLabel("7", 10).shortText)
        assertEquals("Row 8 · Seat R", SeatLabel("8", "R", SeatKind.WHEELCHAIR).shortText)
    }

    @Test
    fun rowsWithoutANameAreLeftOutOfTheTexts() {
        val sofa = SeatLabel(null, 12, SeatKind.SOFA)
        assertEquals("Seat 12", sofa.displayText)
        assertEquals("Seat 12", sofa.shortText)
        assertEquals("Seat 12, sofa", sofa.description)
        assertEquals("Seat R, wheelchair space", SeatLabel(null, "R", SeatKind.WHEELCHAIR).description)
        assertEquals("Seat 3", SeatLabel(null, 3).description)
    }

    @Test
    fun aRowLabelledDashHasNoName() {
        // Kant Kino Saal 3: each double sofa is its own unnamed row, numbered across the room.
        val layout = layoutOf("...FF", "FF...", "...FF")
            .updateRow(0) { it.copy(label = LayoutRow.NO_NAME, blockStartNumbers = listOf(5)) }
            .updateRow(1) { it.copy(label = "-", numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(3)) }
            .updateRow(2) { it.copy(label = "-") }
        assertEquals(listOf(true, true, true), layout.rows.map { it.hasNoName })
        val labels = layout.labels()
        assertEquals(listOf(null, null, null), labels.rowLabels)
        assertEquals(SeatLabel(null, 5, SeatKind.SOFA), labels.labelOf(SeatId(1)))
        assertEquals(SeatLabel(null, 4, SeatKind.SOFA), labels.labelOf(SeatId(3)))
        assertEquals(listOf("4", "3"), layout.texts(1))
        assertEquals(listOf("1", "2"), layout.texts(2))
        assertEquals(6, labels.seats.size)
        assertEquals("Seat 5, sofa", labels.labelOf(SeatId(1))!!.description)
    }

    @Test
    fun unnamedSeatRowsSpacerRowsAndAutomaticRowsStayApart() {
        val layout = layoutOf("SS", "..", "SS", "SS", "..")
            .updateRow(2) { it.copy(label = "-") } // an unnamed seat row
            .updateRow(4) { it.copy(label = "-") } // a spacer row marked "-": still no label
        assertEquals(listOf(false, false, true, false, true), layout.rows.map { it.hasNoName })
        val labels = layout.labels()
        // The spacer row has no label; the unnamed row has none either, but still counts as
        // a seat row: the automatic row after it is row 3.
        assertEquals(listOf("1", null, null, "3", null), labels.rowLabels)
        assertEquals(SeatLabel("1", 1), labels.labelOf(SeatId(1)))
        assertEquals(SeatLabel(null, 2), labels.labelOf(SeatId(4)))
        assertEquals(SeatLabel("3", 1), labels.labelOf(SeatId(5)))
        // An automatic row is never unnamed; only "-" is (not "–" or a blank).
        assertEquals("–", layout.updateRow(0) { it.copy(label = "–") }.labels().rowLabels[0])
        assertNull(layoutOf("SS").rows[0].label)
    }
}
