package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.seatmapui.seatNumberText
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class LayoutEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val stored = SeatLayout.fromPatterns("SSSS", "SSSS", "SSSS", firstSeatId = 1) // ids 1..12
    private val hall = Hall(
        id = HALL,
        cinemaId = 1,
        name = "Saal 1",
        layout = stored,
        screenWidth = 4f,
        screenDistance = 2f,
        referenceImagePath = OLD_IMAGE,
    )
    private val visitInSeat12 = Visit(id = 1, hallId = HALL, date = LocalDate.of(2026, 9, 1), seatId = SeatId(12))

    private val repository = FakeCinexRepository(
        FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall), visits = listOf(visitInSeat12)),
    )
    private val images = FakeReferenceImageStore(setOf(OLD_IMAGE))

    private fun viewModel() = LayoutEditorViewModel(HALL, repository, images)

    private val LayoutEditorViewModel.state get() = uiState.value
    private val LayoutEditorViewModel.draft get() = uiState.value.draft!!
    private fun LayoutEditorViewModel.patterns() = draft.layout.rows.map { LayoutPattern.encode(it.cells) }
    private fun storedHall() = repository.store.value.halls.single { it.id == HALL }

    @Test
    fun loadsTheStoredHall() {
        val vm = viewModel()
        assertEquals(EditorStatus.Ready, vm.state.status)
        assertEquals("Saal 1", vm.state.hallName)
        assertEquals(listOf("SSSS", "SSSS", "SSSS"), vm.patterns())
        assertEquals(4f, vm.draft.screenWidth)
        assertFalse(vm.state.canUndo)
        assertFalse(vm.state.hasUnsavedChanges)
        assertNull(vm.state.row)
    }

    @Test
    fun aPaintStrokeIsOneUndoStep() {
        val vm = viewModel()
        vm.selectTool(CellType.GAP)
        vm.beginGesture()
        vm.paintCells(listOf(CellPosition(0, 0), CellPosition(0, 1)))
        vm.paintCells(listOf(CellPosition(0, 2)))
        vm.endGesture()
        assertEquals("...S", vm.patterns()[0])
        assertTrue(vm.state.canUndo)
        assertTrue(vm.state.hasUnsavedChanges)

        vm.undo()
        assertEquals("SSSS", vm.patterns()[0])
        assertFalse(vm.state.canUndo)
        assertTrue(vm.state.canRedo)
        assertFalse(vm.state.hasUnsavedChanges)
        vm.redo()
        assertEquals("...S", vm.patterns()[0])

        // Toggled back: the seats get their ids back.
        vm.selectTool(CellType.SEAT)
        vm.tapCell(0, 0)
        assertEquals(LayoutCell.Seat(SeatId(1)), vm.draft.layout.cell(0, 0))
    }

    @Test
    fun tapsThatChangeNothingRecordNoStep() {
        val vm = viewModel()
        vm.tapCell(1, 1) // already a seat
        vm.beginGesture()
        vm.endGesture()
        assertFalse(vm.state.canUndo)
        vm.selectTool(CellType.AISLE)
        vm.tapCell(1, 1)
        vm.tapCell(1, 2)
        assertEquals("S||S", vm.patterns()[1])
        vm.undo()
        assertEquals("S|SS", vm.patterns()[1])
    }

    @Test
    fun seatKindsArePaintedOnExistingSeatsKeepingTheirIds() {
        val vm = viewModel()
        vm.selectSeatKind(SeatKind.WHEELCHAIR)
        assertEquals(CellType.SEAT, vm.state.tool)
        vm.tapCell(2, 0)
        vm.selectSeatKind(SeatKind.COMPANION)
        vm.tapCell(2, 1)
        assertEquals(LayoutCell.Seat(SeatId(9), SeatKind.WHEELCHAIR), vm.draft.layout.cell(2, 0))
        assertEquals(LayoutCell.Seat(SeatId(10), SeatKind.COMPANION), vm.draft.layout.cell(2, 1))
        assertEquals(SeatLabel("3", 1, SeatKind.WHEELCHAIR), vm.state.labels!!.labelOf(SeatId(9)))
        assertEquals(SeatLabel("3", 2, SeatKind.COMPANION), vm.state.labels!!.labelOf(SeatId(10)))
        vm.undo()
        assertEquals(LayoutCell.Seat(SeatId(10)), vm.draft.layout.cell(2, 1))
    }

    @Test
    fun selectedRowSettings() {
        val vm = viewModel()
        vm.selectRow(1)
        assertEquals("Row 2 · 1 block", vm.state.row!!.title)
        assertEquals("", vm.state.labelInput)

        // Typing a label is one undo step.
        vm.setRowLabel("B")
        vm.setRowLabel("Bx ")
        assertEquals("Bx ", vm.state.labelInput)
        assertEquals("Bx", vm.draft.layout.rows[1].label)
        vm.undo()
        assertNull(vm.draft.layout.rows[1].label)
        assertEquals("", vm.state.labelInput)
        vm.setRowLabel("TOOLONGLABEL")
        assertEquals("TOOLON", vm.draft.layout.rows[1].label)
        vm.setRowLabel("  ")
        assertNull(vm.draft.layout.rows[1].label) // blank = automatic

        vm.setHalfSeatOffset(true)
        assertTrue(vm.state.row!!.halfSeatOffset)

        // Block starts and numbering.
        vm.selectTool(CellType.AISLE)
        vm.tapCell(1, 2)
        assertEquals(2, vm.state.row!!.blocks.size)
        assertEquals(listOf("", ""), vm.state.blockStartInputs)
        vm.setNumbering(SeatNumbering.RESTART_PER_BLOCK)
        assertEquals(listOf(1, 2, 1), vm.state.labels!!.seats.values.filter { it.position.row == 1 }.map { it.number })
        vm.setBlockStart(1, "1a0")
        assertEquals(listOf("", "10"), vm.state.blockStartInputs)
        assertEquals(listOf("1–2", "10"), vm.state.row!!.blocks.map { it.rangeText })
        vm.setBlockStart(1, "")
        assertEquals(listOf("1–2", "1"), vm.state.row!!.blocks.map { it.rangeText })

        vm.selectRow(1) // tapping the selected row again clears the selection
        assertNull(vm.state.row)
    }

    @Test
    fun typingADashMakesARowUnnamed() {
        val vm = viewModel()
        vm.selectRow(1)
        assertEquals("automatic", vm.state.row!!.labelKindText)
        vm.setRowLabel(" - ")
        assertEquals("-", vm.draft.layout.rows[1].label)
        assertTrue(vm.draft.layout.rows[1].hasNoName)
        with(vm.state.row!!) {
            assertTrue(hasNoName)
            assertNull(label) // no label beside it on the map
            assertEquals("-", customLabel)
            assertEquals("Unnamed row · 1 block", title)
            assertEquals("no name", labelKindText)
        }
        // Its seats read "Seat 1"; the automatic row after it is still row 3.
        assertEquals(SeatLabel(null, 1), vm.state.labels!!.labelOf(SeatId(5)))
        assertEquals(listOf("1", null, "3"), vm.state.labels!!.rowLabels)

        // Selecting the row again shows "-" in the label field.
        vm.selectRow(1)
        vm.selectRow(1)
        assertEquals("-", vm.state.labelInput)

        // Another label names it again.
        vm.setRowLabel("B")
        assertEquals("Row B · 1 block", vm.state.row!!.title)
        assertEquals("custom", vm.state.row!!.labelKindText)
    }

    @Test
    fun theRemovedSeatWarningLeavesOutRowsWithoutAName() {
        val unnamed = viewModel()
        unnamed.selectRow(2)
        unnamed.setRowLabel("-")
        unnamed.save()
        assertTrue(storedHall().layout!!.rows[2].hasNoName)

        val vm = viewModel()
        vm.changeRowCount(-1) // removes seat 12 ("Seat 4" of the unnamed row), which has a visit
        vm.save()
        val dialog = vm.state.dialog as EditorDialog.ConfirmSave
        assertEquals(listOf(SeatLabel(null, 4)), dialog.removedSeats)
        assertTrue(confirmSaveText(dialog).contains("• Seat 4\n"))
    }

    @Test
    fun rowsAndColumnsAreAddedAndRemoved() {
        val vm = viewModel()
        vm.selectTool(CellType.GAP)
        vm.tapCell(0, 0)
        vm.selectRow(0)
        vm.insertRowBelow()
        assertEquals(listOf(".SSS", ".SSS", "SSSS", "SSSS"), vm.patterns())
        assertEquals(1, vm.state.selectedRow) // the new row
        vm.insertRowAbove()
        assertEquals(5, vm.draft.rowCount)
        assertEquals(1, vm.state.selectedRow)
        vm.removeSelectedRow()
        assertEquals(4, vm.draft.rowCount)
        assertEquals(listOf(SeatId(1), SeatId(2), SeatId(3), SeatId(4)).drop(1), vm.draft.layout.rows[0].seatIds)

        vm.changeRowCount(-2)
        assertEquals(2, vm.draft.rowCount)
        vm.changeColumnCount(+2)
        assertEquals(listOf(".SSSSS", ".SSSSS"), vm.patterns())
        assertEquals(6f, vm.draft.screenWidth) // the screen was as wide as the grid and stays so
        vm.changeColumnCount(-5)
        assertEquals(listOf(".", "."), vm.patterns())
        assertEquals(1f, vm.draft.screenWidth)
        vm.changeColumnCount(-1) // at least one column
        assertEquals(1, vm.draft.columnCount)

        vm.changeScreenWidth(+5f)
        assertEquals(2f, vm.draft.screenWidth) // at most twice the grid
        vm.changeColumnCount(+3)
        assertEquals(2f, vm.draft.screenWidth) // a custom width stays
    }

    @Test
    fun screenGeometryFromHandlesAndSteppers() {
        val vm = viewModel()
        vm.beginGesture()
        vm.dragScreenWidth(5.3f)
        vm.dragScreenWidth(6.1f)
        vm.dragScreenDistance(3.24f)
        vm.endGesture()
        assertEquals(6f, vm.draft.screenWidth)
        assertEquals(3f, vm.draft.screenDistance)
        vm.undo() // the whole drag is one step
        assertEquals(4f, vm.draft.screenWidth)
        assertEquals(2f, vm.draft.screenDistance)
        assertFalse(vm.state.canUndo)

        vm.changeScreenDistance(ScreenRules.STEP)
        assertEquals(2.5f, vm.draft.screenDistance)
        vm.changeScreenWidth(-100f)
        assertEquals(1f, vm.draft.screenWidth)
        vm.changeScreenDistance(-100f)
        assertEquals(0f, vm.draft.screenDistance)
    }

    @Test
    fun theScreenPositionStepsSnapsAndUndoes() {
        val vm = viewModel() // 4 columns: the centre can move up to 2 seats either way
        assertEquals(0f, vm.draft.screenOffset)
        vm.changeScreenOffset(-ScreenOffsetRules.STEP)
        vm.changeScreenOffset(-ScreenOffsetRules.STEP)
        assertEquals(-1f, vm.draft.screenOffset)
        assertTrue(vm.state.hasUnsavedChanges)
        vm.changeScreenOffset(-5f)
        assertEquals(-2f, vm.draft.screenOffset) // within the grid
        vm.undo()
        assertEquals(-1f, vm.draft.screenOffset)
        vm.undo()
        vm.undo()
        assertEquals(0f, vm.draft.screenOffset)
        assertFalse(vm.state.hasUnsavedChanges)
        vm.redo()
        assertEquals(-0.5f, vm.draft.screenOffset)

        // A sideways drag of the bar snaps to half seats and is one undo step.
        vm.beginGesture()
        vm.dragScreenOffset(0.3f)
        vm.dragScreenOffset(1.27f)
        vm.dragScreenOffset(1.74f)
        vm.endGesture()
        assertEquals(1.5f, vm.draft.screenOffset)
        vm.undo()
        assertEquals(-0.5f, vm.draft.screenOffset)
    }

    @Test
    fun discardingAMovedScreenAsksFirst() {
        val vm = viewModel()
        vm.changeScreenOffset(1f)
        vm.requestDiscard()
        assertEquals(EditorDialog.ConfirmDiscard, vm.state.dialog)
        vm.discard()
        assertTrue(vm.state.isClosed)
        assertEquals(0f, storedHall().screenOffset) // nothing saved
    }

    @Test
    fun saveWritesTheScreenOffset() {
        val vm = viewModel()
        vm.changeScreenOffset(-1.5f)
        vm.save()
        assertTrue(vm.state.isClosed)
        assertEquals(-1.5f, storedHall().screenOffset)
        assertEquals(stored.rows, storedHall().layout!!.rows) // only the offset changed

        // The stored offset is where the next edit starts.
        val again = viewModel()
        assertEquals(-1.5f, again.draft.screenOffset)
        assertFalse(again.state.hasUnsavedChanges)
    }

    /**
     * A valid stored offset beyond what the grid allows in the editor (possible after an
     * import or from the seed) is kept as it is: opening and saving the editor doesn't
     * change it; only moving the screen or changing the columns limits it to the grid.
     */
    @Test
    fun loadingKeepsAStoredOffsetBeyondTheGrid() {
        val storedOffset = -10f
        check(ScreenOffsetRules.isValid(storedOffset) && storedOffset < -ScreenOffsetRules.maxMagnitude(4)) // 4 columns: ±2
        repository.store.value = repository.store.value.copy(halls = listOf(hall.copy(screenOffset = storedOffset)))

        val vm = viewModel()
        assertEquals(storedOffset, vm.draft.screenOffset)
        assertFalse(vm.state.hasUnsavedChanges)
        // Selecting the screen, changing its distance and width, adding rows: the offset stays.
        vm.selectScreen()
        vm.beginGesture()
        vm.dragScreenDistance(3f)
        vm.endGesture()
        vm.changeScreenWidth(ScreenRules.STEP)
        vm.changeRowCount(+1)
        assertEquals(storedOffset, vm.draft.screenOffset)
        vm.undo()
        vm.undo()
        vm.undo()
        assertFalse(vm.state.hasUnsavedChanges)
        // Saved with another edit: still the stored offset.
        vm.selectTool(CellType.GAP)
        vm.tapCell(0, 0)
        vm.save()
        assertTrue(vm.state.isClosed)
        assertEquals(storedOffset, storedHall().screenOffset)
        assertEquals(".SSS", LayoutPattern.encode(storedHall().layout!!.rows[0].cells))

        // Moving the screen limits it to the grid; undoing that brings the stored offset back.
        val moved = viewModel()
        assertFalse(moved.state.hasUnsavedChanges)
        moved.changeScreenOffset(ScreenOffsetRules.STEP)
        assertEquals(-2f, moved.draft.screenOffset)
        assertTrue(moved.state.hasUnsavedChanges)
        moved.undo()
        assertEquals(storedOffset, moved.draft.screenOffset)
        assertFalse(moved.state.hasUnsavedChanges)

        // So does changing the columns.
        moved.changeColumnCount(+1)
        assertEquals(-2.5f, moved.draft.screenOffset)
        moved.undo()
        assertEquals(storedOffset, moved.draft.screenOffset)
        moved.requestDiscard()
        assertTrue(moved.state.isClosed) // nothing to ask: unchanged
        assertEquals(storedOffset, storedHall().screenOffset)
    }

    @Test
    fun tappingTheScreenSelectsItAndClearsTheRow() {
        val vm = viewModel()
        vm.selectRow(1)
        vm.selectScreen()
        assertTrue(vm.state.screenSelected)
        assertNull(vm.state.selectedRow) // the screen panel replaces the row panel
        assertNull(vm.state.row)
        // Selecting is not an edit.
        assertFalse(vm.state.canUndo)
        assertFalse(vm.state.hasUnsavedChanges)

        // Dragging the selected screen keeps it selected and is one undo step.
        vm.beginGesture()
        vm.dragScreenWidth(6f)
        vm.endGesture()
        assertTrue(vm.state.screenSelected)
        vm.undo()
        assertEquals(4f, vm.draft.screenWidth)
        assertTrue(vm.state.screenSelected) // undo keeps the selection
        vm.redo()
        assertEquals(6f, vm.draft.screenWidth)
        assertTrue(vm.state.screenSelected)
    }

    @Test
    fun cellsRowLabelsPaintingAndTapsBesideTheGridDeselectTheScreen() {
        val vm = viewModel()
        vm.selectScreen()
        vm.tapCell(0, 0) // already a seat: nothing painted, but deselected
        assertFalse(vm.state.screenSelected)

        vm.selectScreen()
        vm.selectRow(2)
        assertFalse(vm.state.screenSelected)
        assertEquals(2, vm.state.selectedRow)

        vm.selectScreen()
        vm.selectTool(CellType.GAP)
        vm.beginGesture()
        vm.paintCells(listOf(CellPosition(1, 0), CellPosition(1, 1)))
        vm.endGesture()
        assertFalse(vm.state.screenSelected)
        assertEquals("..SS", vm.patterns()[1])

        vm.selectScreen()
        vm.tapOutside()
        assertFalse(vm.state.screenSelected)

        vm.selectScreen()
        vm.deselectScreen() // "Done"
        assertFalse(vm.state.screenSelected)
    }

    @Test
    fun theReferenceModeAndTheScreenSelectionExcludeEachOther() {
        val vm = viewModel()
        vm.selectScreen()
        vm.toggleReferenceMode()
        assertTrue(vm.state.referenceMode)
        assertFalse(vm.state.screenSelected)
        vm.selectScreen() // the canvas aligns the screenshot in reference mode
        assertFalse(vm.state.screenSelected)
    }

    @Test
    fun seatNumbersFollowEveryEditLive() {
        val vm = viewModel()
        fun numbers(row: Int) = vm.state.labels!!.seats.values
            .filter { it.position.row == row }
            .sortedBy { it.position.column }
            .map { it.label.seatNumberText }
        assertEquals(listOf("1", "2", "3", "4"), numbers(0))

        // Painting: an aisle splits the row, a wheelchair space is numbered like every seat.
        vm.selectTool(CellType.AISLE)
        vm.tapCell(0, 1)
        assertEquals(listOf("1", "2", "3"), numbers(0))
        vm.selectSeatKind(SeatKind.WHEELCHAIR)
        vm.tapCell(0, 3)
        assertEquals(listOf("1", "2", "3"), numbers(0))

        // Block numbering and start numbers.
        vm.selectRow(0)
        vm.setNumbering(SeatNumbering.RESTART_PER_BLOCK)
        assertEquals(listOf("1", "1", "2"), numbers(0))
        vm.setBlockStart(1, "12")
        assertEquals(listOf("1", "12", "13"), numbers(0))

        // Undo brings the numbers back as well.
        vm.undo()
        assertEquals(listOf("1", "1", "2"), numbers(0))
    }

    @Test
    fun numberingDirectionIsARowSettingWithUndo() {
        val vm = viewModel()
        fun numbers(row: Int) = vm.state.labels!!.seats.values
            .filter { it.position.row == row }
            .sortedBy { it.position.column }
            .map { it.label.seatNumberText }
        vm.selectTool(CellType.AISLE)
        vm.tapCell(1, 1) // row 2: S | S S
        vm.selectRow(1)
        assertEquals(NumberingDirection.LEFT_TO_RIGHT, vm.state.row!!.numberingDirection)

        // Right to left: numbers update live, from the rightmost seat.
        vm.setNumberingDirection(NumberingDirection.RIGHT_TO_LEFT)
        assertEquals(NumberingDirection.RIGHT_TO_LEFT, vm.draft.layout.rows[1].numberingDirection)
        assertEquals(listOf("3", "2", "1"), numbers(1))
        assertEquals(listOf("1", "2", "3", "4"), numbers(0)) // other rows keep theirs
        assertTrue(vm.state.hasUnsavedChanges)
        // The block fields follow the direction: the right block (index 1) comes first.
        assertEquals(listOf(1, 0), vm.state.row!!.blocksInNumberingOrder.map { it.index })
        assertEquals(listOf("1–2", "3"), vm.state.row!!.blocksInNumberingOrder.map { it.rangeText })

        // A block start is still kept by its block (counted from the left).
        vm.setBlockStart(0, "7")
        assertEquals(listOf("7", ""), vm.state.blockStartInputs)
        assertEquals(listOf("7", "2", "1"), numbers(1))
        vm.setNumberingDirection(NumberingDirection.LEFT_TO_RIGHT)
        assertEquals(listOf("7", "8", "9"), numbers(1))
        assertEquals(listOf("7", ""), vm.state.blockStartInputs)

        // Each change is one undo step; setting the same direction again is none.
        vm.undo()
        assertEquals(listOf("7", "2", "1"), numbers(1))
        vm.setNumberingDirection(NumberingDirection.RIGHT_TO_LEFT)
        vm.undo()
        assertEquals(listOf("3", "2", "1"), numbers(1))
        assertEquals(listOf("", ""), vm.state.blockStartInputs)
        vm.undo()
        assertEquals(listOf("1", "2", "3"), numbers(1))
        vm.redo()
        assertEquals(listOf("3", "2", "1"), numbers(1))

        // Saved with the layout; discarding leaves the stored hall untouched.
        vm.save()
        assertEquals(NumberingDirection.RIGHT_TO_LEFT, storedHall().layout!!.rows[1].numberingDirection)
    }

    @Test
    fun discardDropsADirectionChange() {
        val vm = viewModel()
        vm.selectRow(0)
        vm.setNumberingDirection(NumberingDirection.RIGHT_TO_LEFT)
        assertTrue(vm.state.hasUnsavedChanges)
        vm.requestDiscard()
        assertEquals(EditorDialog.ConfirmDiscard, vm.state.dialog)
        vm.discard()
        assertTrue(vm.state.isClosed)
        assertEquals(NumberingDirection.LEFT_TO_RIGHT, storedHall().layout!!.rows[0].numberingDirection)
    }

    @Test
    fun fixedLabelsAreShownAndKeptThroughEdits() {
        val kant = SeatLayout.fromPatterns("SSSSSSW", "SSSSSSS", firstSeatId = 1)
            .updateRow(0) { it.copy(label = "8", numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
            .withFixedLabel(0, 6, "R")
        repository.store.value = repository.store.value.copy(
            halls = listOf(hall.copy(layout = kant, nextSeatId = 15)),
            visits = emptyList(),
        )
        val vm = viewModel()
        fun texts(row: Int) = vm.state.labels!!.seats.values
            .filter { it.position.row == row }
            .sortedBy { it.position.column }
            .map { it.label.seatNumberText }
        assertEquals(listOf("6", "5", "4", "3", "2", "1", "R"), texts(0))
        vm.selectRow(0)
        // Right to left, the "R" at the right end comes first in numbering order.
        assertEquals("R + 1–6", vm.state.row!!.blocks.single().rangeText)

        // Rows and columns added around it, another kind: the label stays on its seat.
        vm.insertRowAbove()
        vm.changeColumnCount(1)
        vm.selectSeatKind(SeatKind.COMPANION)
        vm.tapCell(1, 6)
        assertEquals(LayoutCell.Seat(SeatId(7), SeatKind.COMPANION, "R"), vm.draft.layout.cell(1, 6))
        assertEquals(listOf("7", "6", "5", "4", "3", "2", "R", "1"), texts(1))

        // Painted as a gap: gone from the map. Painted back as a seat: the same seat with its "R"
        // (the editor can't create fixed labels, so it must not lose one).
        vm.selectTool(CellType.GAP)
        vm.tapCell(1, 6)
        assertTrue(vm.draft.layout.rows[1].fixedLabels.all { it == null })
        assertEquals(listOf("7", "6", "5", "4", "3", "2", "1"), texts(1))
        vm.selectTool(CellType.SEAT)
        vm.tapCell(1, 6)
        assertEquals(LayoutCell.Seat(SeatId(7), SeatKind.COMPANION, "R"), vm.draft.layout.cell(1, 6))
        assertEquals(listOf("7", "6", "5", "4", "3", "2", "R", "1"), texts(1))
        vm.undo()
        assertTrue(vm.draft.layout.rows[1].fixedLabels.all { it == null }) // undo: the gap again
        vm.undo()
        assertEquals(LayoutCell.Seat(SeatId(7), SeatKind.COMPANION, "R"), vm.draft.layout.cell(1, 6))

        // Saved as it is.
        vm.save()
        assertEquals("R", (storedHall().layout!!.cell(1, 6) as LayoutCell.Seat).fixedLabel)
    }

    @Test
    fun saveWritesTheHallAndCloses() {
        val vm = viewModel()
        vm.selectTool(CellType.AISLE)
        vm.tapCell(0, 1)
        vm.changeScreenWidth(1f)
        vm.save()
        assertTrue(vm.state.isClosed)
        assertNull(vm.state.dialog)
        val saved = storedHall()
        assertEquals("S|SS", LayoutPattern.encode(saved.layout!!.rows[0].cells))
        assertEquals(5f, saved.screenWidth)
        assertEquals(OLD_IMAGE, saved.referenceImagePath)
        assertTrue(images.deleted.isEmpty())
    }

    @Test
    fun saveWarnsBeforeRemovingSeatsWithVisits() {
        val vm = viewModel()
        vm.changeRowCount(-1) // removes seat 12, which has a visit
        vm.save()
        val dialog = vm.state.dialog as EditorDialog.ConfirmSave
        assertEquals(listOf(SeatLabel("3", 4)), dialog.removedSeats)
        assertFalse(dialog.replacesUnreadableLayout)
        assertTrue(confirmSaveText(dialog).contains("Row 3, Seat 4"))

        // Cancel: nothing saved, still editing.
        vm.dismissDialog()
        assertFalse(vm.state.isClosed)
        assertEquals(3, storedHall().layout!!.rowCount)

        // Confirm: saved.
        vm.save()
        vm.confirmSave()
        assertTrue(vm.state.isClosed)
        assertEquals(2, storedHall().layout!!.rowCount)
    }

    @Test
    fun removingSeatsWithoutDataSavesWithoutAsking() {
        val vm = viewModel()
        vm.selectTool(CellType.GAP)
        vm.tapCell(0, 0)
        vm.save()
        assertNull(vm.state.dialog)
        assertTrue(vm.state.isClosed)
    }

    @Test
    fun aStaleSessionCanBeReloaded() {
        val vm = viewModel()
        vm.changeRowCount(+1) // new seats 13..16 in a new back row

        // Meanwhile the stored layout changed: a front row with ids 13..16 was added elsewhere.
        val other = storedHall().startLayoutEdit().insertRow(0)
        runBlocking { repository.updateHall(storedHall().copy(layout = other.layout)) }

        vm.save()
        val dialog = vm.state.dialog as EditorDialog.SaveFailed
        assertTrue(dialog.canReload)
        assertEquals(LayoutEditorViewModel.STALE_EDIT, dialog.message)
        assertFalse(vm.state.isClosed)
        assertEquals(other.layout, storedHall().layout) // nothing overwritten

        vm.reload()
        assertNull(vm.state.dialog)
        assertEquals(4, vm.draft.rowCount)
        assertEquals(other.layout!!.rows, vm.draft.layout.rows)
        assertFalse(vm.state.canUndo)
        assertFalse(vm.state.hasUnsavedChanges)
    }

    @Test
    fun otherSaveFailuresShowAMessage() {
        val vm = viewModel()
        vm.tapCell(0, 0)
        vm.selectTool(CellType.GAP)
        vm.tapCell(0, 0)
        repository.failure = IllegalStateException("disk full")
        vm.save()
        assertEquals(EditorDialog.SaveFailed(LayoutEditorViewModel.SAVE_FAILED, canReload = false), vm.state.dialog)
        vm.dismissDialog()
        repository.failure = null
        vm.save()
        assertTrue(vm.state.isClosed)
    }

    @Test
    fun discardAsksOnlyWithUnsavedChanges() {
        val untouched = viewModel()
        untouched.requestDiscard()
        assertTrue(untouched.state.isClosed)

        val vm = viewModel()
        vm.selectTool(CellType.GAP)
        vm.tapCell(0, 0)
        vm.requestDiscard()
        assertEquals(EditorDialog.ConfirmDiscard, vm.state.dialog)
        vm.dismissDialog()
        assertFalse(vm.state.isClosed)
        vm.requestDiscard()
        vm.discard()
        assertTrue(vm.state.isClosed)
        assertEquals(stored, storedHall().layout)

        // Undoing every change counts as unchanged again.
        val undone = viewModel()
        undone.selectTool(CellType.GAP)
        undone.tapCell(0, 0)
        undone.undo()
        undone.requestDiscard()
        assertTrue(undone.state.isClosed)
    }

    @Test
    fun referenceImageCanBeReplacedUndoneAndRemoved() {
        val vm = viewModel()
        vm.onReferenceImagePicked("content://picker/1")
        val first = "reference/hall-$HALL-1.jpg"
        assertEquals(first, vm.draft.referenceImagePath)
        assertTrue(vm.state.referenceMode)
        assertTrue(vm.state.hasUnsavedChanges)
        assertEquals(listOf("content://picker/1"), images.importedSources)

        vm.setReferenceAlignment(ReferenceAlignment(1f, 2f, 30f))
        vm.setReferenceOpacity(2f)
        assertEquals(LayoutEditorUiState.MAX_REFERENCE_OPACITY, vm.state.referenceOpacity)

        vm.undo()
        assertEquals(OLD_IMAGE, vm.draft.referenceImagePath)
        assertNull(vm.state.referenceAlignment) // a different image starts at the default placement
        vm.redo()
        assertEquals(first, vm.draft.referenceImagePath)

        vm.removeReferenceImage()
        assertNull(vm.draft.referenceImagePath)
        vm.onReferenceImagePicked("content://picker/2")
        val second = "reference/hall-$HALL-2.jpg"
        vm.onReferenceImagePicked(null) // cancelled picker: nothing happens
        assertEquals(second, vm.draft.referenceImagePath)

        vm.save()
        assertTrue(vm.state.isClosed)
        assertEquals(second, storedHall().referenceImagePath)
        assertEquals(setOf(first, OLD_IMAGE), images.deleted.toSet()) // the unused import and the replaced file
        assertEquals(setOf(second), images.files)
    }

    @Test
    fun discardingDeletesOnlyScreenshotsPickedInThisSession() {
        val vm = viewModel()
        vm.onReferenceImagePicked("content://picker/1")
        vm.requestDiscard()
        vm.discard()
        assertEquals(listOf("reference/hall-$HALL-1.jpg"), images.deleted)
        assertEquals(setOf(OLD_IMAGE), images.files)
        assertEquals(OLD_IMAGE, storedHall().referenceImagePath)
    }

    @Test
    fun removingTheScreenshotDeletesItsFileOnlyAfterSaving() {
        val vm = viewModel()
        vm.removeReferenceImage()
        assertTrue(images.deleted.isEmpty())
        vm.save()
        assertNull(storedHall().referenceImagePath)
        assertEquals(listOf(OLD_IMAGE), images.deleted)
    }

    @Test
    fun aFailedImportShowsAMessage() {
        val vm = viewModel()
        images.importFailure = IOException("unreadable")
        vm.onReferenceImagePicked("content://picker/1")
        assertEquals(LayoutEditorViewModel.IMPORT_FAILED, vm.state.message)
        assertFalse(vm.state.isImportingReference)
        assertEquals(OLD_IMAGE, vm.draft.referenceImagePath)
        vm.messageShown()
        assertNull(vm.state.message)
    }

    @Test
    fun aHallWithoutLayoutStartsWithTheDefaultGrid() {
        repository.store.value = repository.store.value.copy(
            halls = listOf(hall.copy(layout = null, screenWidth = null, screenDistance = null, nextSeatId = 50)),
            visits = emptyList(),
        )
        val vm = viewModel()
        assertEquals(10, vm.draft.rowCount)
        assertEquals(16, vm.draft.columnCount)
        assertEquals(16f, vm.draft.screenWidth)
        assertEquals(2f, vm.draft.screenDistance)
        assertFalse(vm.state.hasUnsavedChanges)
        vm.save() // even unchanged, the default grid is saved
        assertTrue(vm.state.isClosed)
        val saved = storedHall()
        assertEquals(160, saved.layout!!.seatCount)
        assertTrue(saved.layout!!.seatIds.all { it.value >= 50 })
        assertEquals(16f, saved.screenWidth)
        assertEquals(2f, saved.screenDistance)
    }

    @Test
    fun replacingAnUnreadableLayoutAsksFirst() {
        repository.store.value = repository.store.value.copy(
            halls = listOf(hall.copy(layout = null, layoutUnreadable = true, nextSeatId = 13)),
        )
        val vm = viewModel()
        assertTrue(vm.state.replacesUnreadableLayout)
        vm.save()
        // The visit in seat 12 loses its seat: its count is part of the warning.
        val dialog = EditorDialog.ConfirmSave(emptyList(), replacesUnreadableLayout = true, unreadableSeatsWithData = 1)
        assertEquals(dialog, vm.state.dialog)
        assertTrue(confirmSaveText(dialog).contains("1 of its seats has visits or seat settings"))
        assertTrue(
            confirmSaveText(dialog.copy(unreadableSeatsWithData = 3)).contains("3 of its seats have visits or seat settings"),
        )
        vm.confirmSave()
        assertTrue(vm.state.isClosed)
        assertFalse(storedHall().layoutUnreadable)
        assertEquals(160, storedHall().layout!!.seatCount)
    }

    @Test
    fun anUnreadableLayoutWithoutSeatDataWarnsWithoutCount() {
        repository.store.value = repository.store.value.copy(
            halls = listOf(hall.copy(layout = null, layoutUnreadable = true, nextSeatId = 13)),
            visits = listOf(visitInSeat12.copy(seatId = null)),
        )
        val vm = viewModel()
        vm.save()
        assertEquals(EditorDialog.ConfirmSave(emptyList(), replacesUnreadableLayout = true, unreadableSeatsWithData = 0), vm.state.dialog)
        assertFalse(confirmSaveText(vm.state.dialog as EditorDialog.ConfirmSave).contains("visits"))
    }

    /** A save waiting on a slow repository can't be undercut by Discard or Back. */
    @Test
    fun discardIsIgnoredWhileASaveIsRunning() {
        val slow = SlowHallRepository(repository)
        val vm = LayoutEditorViewModel(HALL, slow, images)
        vm.onReferenceImagePicked("content://picker/1")
        val picked = vm.draft.referenceImagePath!!
        vm.save()
        assertTrue(vm.state.isSaving)

        vm.requestDiscard()
        vm.discard()
        vm.onReferenceImagePicked("content://picker/2") // no new imports mid-save either
        assertNull(vm.state.dialog)
        assertFalse(vm.state.isClosed)
        assertTrue(picked in images.files)
        assertEquals(1, images.importedSources.size)

        slow.gate.complete(Unit)
        assertTrue(vm.state.isClosed)
        assertFalse(vm.state.isSaving)
        assertEquals(picked, storedHall().referenceImagePath)
        assertTrue(picked in images.files) // the saved screenshot still exists
    }

    /** Delegates to the fake but suspends [updateHall] until [gate] completes. */
    private class SlowHallRepository(private val delegate: FakeCinexRepository) : HallRepository by delegate {
        val gate = CompletableDeferred<Unit>()

        override suspend fun updateHall(hall: Hall): Boolean {
            gate.await()
            return delegate.updateHall(hall)
        }
    }

    @Test
    fun missingHallAndLoadErrors() {
        val missing = LayoutEditorViewModel(999, repository, images)
        assertEquals(EditorStatus.NotFound, missing.state.status)
        missing.save()
        assertFalse(missing.state.isClosed)
        missing.requestDiscard()
        assertTrue(missing.state.isClosed)

        repository.observeFailure = IllegalStateException("db")
        val failing = viewModel()
        assertEquals(EditorStatus.LoadFailed, failing.state.status)
        repository.observeFailure = null
        failing.reload()
        assertEquals(EditorStatus.Ready, failing.state.status)
    }

    @Test
    fun labelsOfTheDraftFollowEveryEdit() {
        val vm = viewModel()
        vm.selectRow(0)
        vm.setRowLabel("A")
        assertEquals(listOf("A", "2", "3"), vm.state.labels!!.rowLabels)
        assertEquals(vm.draft.layout.labels(), vm.state.labels)
    }

    private companion object {
        const val HALL = 10L
        const val OLD_IMAGE = "reference/hall-10-old.jpg"
    }
}
