package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatIdsExhaustedException
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

/**
 * The editor refuses only edits that run out of seat ids (with a message); any
 * other exception from the model is a bug and is not swallowed.
 */
class LayoutEditorEditFailureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(layout: SeatLayout, nextSeatId: Int): LayoutEditorViewModel {
        val hall = Hall(id = 7, cinemaId = 1, name = "Saal 1", layout = layout, nextSeatId = nextSeatId)
        val repository = FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall)))
        return LayoutEditorViewModel(hall.id, repository, FakeReferenceImageStore()).also {
            assertEquals(EditorStatus.Ready, it.uiState.value.status)
        }
    }

    private val LayoutEditorViewModel.patterns get() = uiState.value.draft!!.layout.rows.map { LayoutPattern.encode(it.cells) }

    @Test
    fun runningOutOfSeatIdsShowsTheMessage() {
        val vm = viewModel(SeatLayout.fromPatterns("SS.", firstSeatId = MAX_SEAT_ID - 2), nextSeatId = MAX_SEAT_ID)
        vm.tapCell(0, 2) // gap → seat would need the id MAX_SEAT_ID
        assertEquals(listOf("SS."), vm.patterns)
        assertEquals(LayoutEditorViewModel.EDIT_FAILED, vm.uiState.value.message)
        assertFalse(vm.uiState.value.canUndo)

        vm.messageShown()
        vm.edit { throw SeatIdsExhaustedException("No seat id left") }
        assertEquals(LayoutEditorViewModel.EDIT_FAILED, vm.uiState.value.message)
    }

    @Test
    fun anyOtherModelFailureIsNotSwallowed() {
        val vm = viewModel(SeatLayout.fromPatterns("SS", firstSeatId = 1), nextSeatId = 3)
        val bug = IllegalArgumentException("Cell (0, 0) is already a seat")
        val thrown = assertThrows(IllegalArgumentException::class.java) { vm.edit { throw bug } }
        assertEquals(bug, thrown)
        // A real one: the model refuses a seat on a seat.
        assertThrows(IllegalArgumentException::class.java) {
            vm.edit { draft -> draft.also { it.layout.withSeat(0, 0, SeatId(9)) } }
        }
        assertNull(vm.uiState.value.message)
        assertEquals(listOf("SS"), vm.patterns)
    }
}
