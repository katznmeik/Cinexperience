package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutDraftTest {

    private val stored = SeatLayout.fromPatterns("SS|SS", ".S|S.", firstSeatId = 1) // ids 1..6
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = stored, screenWidth = 4f, screenDistance = 1.5f, nextSeatId = 10)

    private fun LayoutDraft.patterns() = layout.rows.map { LayoutPattern.encode(it.cells) }

    @Test
    fun startUsesTheStoredHallOrADefaultGridAboveTheIdMark() {
        val draft = LayoutDraft.start(hall)
        assertEquals(listOf("SS|SS", ".S|S."), draft.patterns())
        assertEquals(4f, draft.screenWidth)
        assertEquals(1.5f, draft.screenDistance)

        val empty = LayoutDraft.start(Hall(id = 2, cinemaId = 1, name = "Saal 2", nextSeatId = 40, referenceImagePath = "reference/a.jpg"))
        assertEquals(LayoutDraft.DEFAULT_ROWS, empty.rowCount)
        assertEquals(LayoutDraft.DEFAULT_COLUMNS, empty.columnCount)
        assertEquals(160, empty.layout.seatCount)
        assertTrue(empty.layout.seatIds.all { it.value >= 40 }) // never reuses the hall's old ids
        assertEquals(16f, empty.screenWidth)
        assertEquals(2f, empty.screenDistance)
        assertEquals("reference/a.jpg", empty.referenceImagePath)
    }

    @Test
    fun paintingSetsTypesAndKindsAndKeepsSeatIds() {
        val draft = LayoutDraft.start(hall)
        val gap = draft.paint(0, 0, CellType.GAP)
        assertEquals(LayoutCell.Gap, gap.layout.cell(0, 0))
        val back = gap.paint(0, 0, CellType.SEAT)
        assertEquals(LayoutCell.Seat(SeatId(1)), back.layout.cell(0, 0)) // original id restored
        assertTrue(back.hasSameContentAs(draft))

        val wheelchair = draft.paint(0, 1, CellType.SEAT, SeatKind.WHEELCHAIR)
        assertEquals(LayoutCell.Seat(SeatId(2), SeatKind.WHEELCHAIR), wheelchair.layout.cell(0, 1))
        val newSeat = draft.paint(1, 0, CellType.SEAT, SeatKind.SOFA)
        assertEquals(LayoutCell.Seat(SeatId(10), SeatKind.SOFA), newSeat.layout.cell(1, 0))

        assertSame(draft, draft.paint(0, 0, CellType.SEAT)) // already a standard seat
        assertSame(draft, draft.paint(5, 0, CellType.GAP)) // outside the grid
    }

    @Test
    fun rowAndColumnCountsCopyTheirNeighbours() {
        val draft = LayoutDraft.start(hall)
        val grown = draft.withRowCount(4)
        assertEquals(listOf("SS|SS", ".S|S.", ".S|S.", ".S|S."), grown.patterns())
        assertEquals(listOf(SeatId(10), SeatId(11)), grown.layout.rows[2].seatIds) // fresh ids
        assertEquals(listOf("SS|SS"), draft.withRowCount(1).patterns())
        assertEquals(1, draft.withRowCount(0).rowCount)
        assertEquals(LayoutDraft.MAX_ROWS, draft.withRowCount(999).rowCount)

        val wider = draft.withColumnCount(6)
        assertEquals(listOf("SS|SSS", ".S|S.."), wider.patterns())
        assertEquals(listOf("SS", ".S"), draft.withColumnCount(2).patterns())
    }

    @Test
    fun screenWidthFollowsTheGridOnlyWhileItMatches() {
        val matching = LayoutDraft.start(hall.copy(screenWidth = 5f))
        assertEquals(7f, matching.withColumnCount(7).screenWidth)
        assertEquals(3f, matching.withColumnCount(3).screenWidth)
        val custom = LayoutDraft.start(hall) // 4 seats wide on 5 columns
        assertEquals(4f, custom.withColumnCount(7).screenWidth)
        // ... but never wider than twice the grid.
        assertEquals(2f, custom.withColumnCount(1).screenWidth)
    }

    @Test
    fun insertAndRemoveRows() {
        val draft = LayoutDraft.start(hall)
        val inserted = draft.insertRowCopy(1, template = 0)
        assertEquals(listOf("SS|SS", "SS|SS", ".S|S."), inserted.patterns())
        assertTrue(inserted.layout.isEditOf(stored))
        assertEquals(listOf(SeatId(5), SeatId(6)), inserted.layout.rows[2].seatIds) // existing seats keep ids
        val removed = inserted.removeRow(0)
        assertEquals(listOf("SS|SS", ".S|S."), removed.patterns())
        val single = removed.removeRow(0)
        assertSame(single, single.removeRow(0)) // the last row stays
        assertSame(single, single.removeRow(5))
    }

    @Test
    fun screenRulesSnapLimitAndFormat() {
        assertEquals(14.5f, ScreenRules.width(14.4f, columns = 16))
        assertEquals(32f, ScreenRules.width(50f, columns = 16))
        assertEquals(1f, ScreenRules.width(0.2f, columns = 16))
        assertEquals(0f, ScreenRules.distance(-3f))
        assertEquals(15f, ScreenRules.distance(40f))
        assertEquals(2.5f, ScreenRules.distance(2.7f))
        assertEquals("14 seats", ScreenRules.widthText(14f))
        assertEquals("14.5 seats", ScreenRules.widthText(14.5f))
        assertEquals("1 seat", ScreenRules.widthText(1f))
        assertEquals("2.0 rows", ScreenRules.distanceText(2f))
        assertEquals("1.0 row", ScreenRules.distanceText(1f))

        val draft = LayoutDraft.start(hall)
        assertEquals(4.5f, draft.withScreenWidth(4.4f).screenWidth)
        assertEquals(2f, draft.withScreenDistance(2.1f).screenDistance)
    }

    @Test
    fun theScreenOffsetSnapsToHalfSeatsWithinTheGrid() {
        val draft = LayoutDraft.start(hall.copy(screenOffset = -1.5f)) // 5 columns: at most 2.5 seats either way
        assertEquals(-1.5f, draft.screenOffset)
        assertEquals(1f, draft.withScreenOffset(1.2f).screenOffset)
        assertEquals(-2.5f, draft.withScreenOffset(-9f).screenOffset)
        assertEquals(2.5f, draft.withScreenOffset(4f).screenOffset)
        assertEquals(0f, draft.withScreenOffset(Float.NaN).screenOffset)
        assertEquals(0f, draft.withScreenOffset(-0.1f).screenOffset) // no -0.0
        assertTrue(draft.withScreenOffset(-0.1f).screenOffset.toBits() == 0f.toBits())

        // The offset is part of the saved content (unsaved changes, undo).
        assertFalse(draft.withScreenOffset(1f).hasSameContentAs(draft))
        assertTrue(draft.withScreenOffset(-1.4f).hasSameContentAs(draft))

        // Fewer columns: the screen's centre stays over the grid.
        assertEquals(-1f, draft.withScreenOffset(-2.5f).withColumnCount(2).screenOffset)
        assertEquals(-2.5f, draft.withScreenOffset(-2.5f).withColumnCount(9).screenOffset)

        // A hall without an offset starts centred.
        assertEquals(0f, LayoutDraft.start(hall).screenOffset)
    }

    @Test
    fun screenOffsetTexts() {
        assertEquals("Centred", ScreenRules.offsetText(0f))
        assertEquals("1.5 seats left", ScreenRules.offsetText(-1.5f))
        assertEquals("2 seats right", ScreenRules.offsetText(2f))
        assertEquals("1 seat left", ScreenRules.offsetText(-1f))
        assertEquals("0.5 seats right", ScreenRules.offsetText(0.5f))
    }

    @Test
    fun rowSummaryDerivesLabelsBlocksAndRanges() {
        val layout = SeatLayout.fromPatterns("SS|SSS|WS", "|||||||||", "SSSSSSSS.", firstSeatId = 1)
            .updateRow(0) { it.copy(label = "C", numbering = SeatNumbering.CONTINUOUS, blockStartNumbers = listOf(null, 10)) }
            .updateRow(2) { it.copy(halfSeatOffset = true) }
        val labels = layout.labels()

        val row = rowSummary(layout, labels, 0)
        assertEquals("Row C · 3 blocks", row.title)
        assertEquals("C", row.customLabel)
        assertEquals("1", row.autoLabel)
        assertEquals(listOf("1–2", "10–12", "13 + 1 wheelchair"), row.blocks.map { it.rangeText })
        assertEquals(listOf(null, 10, null), row.blocks.map { it.startNumber })

        val aisle = rowSummary(layout, labels, 1)
        assertEquals("Row – · no seats", aisle.title)
        assertNull(aisle.autoLabel)

        val back = rowSummary(layout, labels, 2)
        assertEquals("Row 2 · 1 block", back.title)
        assertTrue(back.halfSeatOffset)
        assertFalse(row.halfSeatOffset)
        assertEquals("1–8", back.blocks.single().rangeText)

        val onlyWheelchairs = BlockSummary(0, null, null, null, wheelchairSpaces = 2)
        assertEquals("2 wheelchairs", onlyWheelchairs.rangeText)
    }
}
