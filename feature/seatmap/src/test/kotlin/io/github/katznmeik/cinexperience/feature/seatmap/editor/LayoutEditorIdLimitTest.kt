package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * A hall whose seat id mark is at or near [MAX_SEAT_ID] (only damaged or crafted
 * data has one) never crashes the editor: it shows "Couldn't load", or refuses
 * just the edits that would need a new seat id.
 */
class LayoutEditorIdLimitTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(hall: Hall): LayoutEditorViewModel {
        val repository = FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall)))
        return LayoutEditorViewModel(hall.id, repository, FakeReferenceImageStore())
    }

    private val LayoutEditorViewModel.patterns get() = uiState.value.draft!!.layout.rows.map { LayoutPattern.encode(it.cells) }

    @Test
    fun aHallWhoseNewGridWouldPassTheLimitFailsToLoad() {
        // A hall without a layout starts with a 10 × 16 grid of new seats: 160 ids from the mark.
        val vm = viewModel(Hall(id = 7, cinemaId = 1, name = "Saal 1", nextSeatId = MAX_SEAT_ID - 5))
        assertEquals(EditorStatus.LoadFailed, vm.uiState.value.status)
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun aHallWhoseMarkIsBeyondTheLimitFailsToLoad() {
        val layout = SeatLayout.fromPatterns("SS", firstSeatId = 1)
        val vm = viewModel(Hall(id = 7, cinemaId = 1, name = "Saal 1", layout = layout, nextSeatId = MAX_SEAT_ID + 1))
        assertEquals(EditorStatus.LoadFailed, vm.uiState.value.status)
    }

    @Test
    fun editsThatNeedANewSeatIdAreRefusedAtTheLimit() {
        val layout = SeatLayout.fromPatterns("SS.", firstSeatId = MAX_SEAT_ID - 2) // next id = MAX_SEAT_ID
        val vm = viewModel(Hall(id = 7, cinemaId = 1, name = "Saal 1", layout = layout, nextSeatId = MAX_SEAT_ID))
        assertEquals(EditorStatus.Ready, vm.uiState.value.status)

        vm.tapCell(0, 2) // gap → seat would need the id MAX_SEAT_ID
        assertEquals(listOf("SS."), vm.patterns)
        assertEquals(LayoutEditorViewModel.EDIT_FAILED, vm.uiState.value.message)
        assertFalse(vm.uiState.value.canUndo)
        vm.messageShown()

        vm.changeRowCount(+1) // a copy of the row needs two new ids
        assertEquals(listOf("SS."), vm.patterns)
        assertEquals(LayoutEditorViewModel.EDIT_FAILED, vm.uiState.value.message)
        vm.messageShown()

        // Edits without new ids still work, and a painted-over seat gets its id back.
        vm.selectTool(CellType.GAP)
        vm.tapCell(0, 0)
        assertEquals(listOf(".S."), vm.patterns)
        vm.selectTool(CellType.SEAT)
        vm.tapCell(0, 0)
        assertEquals(listOf("SS."), vm.patterns)
        assertEquals(layout.rows, vm.uiState.value.draft!!.layout.rows)
        assertNull(vm.uiState.value.message)
    }
}
