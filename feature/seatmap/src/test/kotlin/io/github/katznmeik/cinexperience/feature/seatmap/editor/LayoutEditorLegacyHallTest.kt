package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_LONG_TEXT_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_NAME_LENGTH
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A hall with values an older build stored beyond today's limits (a name over
 * 1,000 code points, notes over 20,000, a 40-character row label, a far too wide
 * and far screen) can still be edited and saved: the save keeps them and never
 * ends in "changed while you were editing", which Reload couldn't fix.
 */
class LayoutEditorLegacyHallTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val legacy = Hall(
        id = 7,
        cinemaId = 1,
        name = "N".repeat(MAX_NAME_LENGTH + 1),
        layout = SeatLayout.fromPatterns("SSS", "SSS", firstSeatId = 1).updateRow(0) { it.copy(label = "R".repeat(40)) },
        screenWidth = 3e38f,
        screenDistance = 40f,
        notes = "L".repeat(MAX_LONG_TEXT_LENGTH + 1),
        nextSeatId = 7,
    )
    private val repository = FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(legacy)))

    private fun storedHall() = repository.store.value.halls.single { it.id == legacy.id }

    @Test
    fun aLayoutChangeIsSavedAndTheStoredValuesAreKept() {
        val vm = LayoutEditorViewModel(legacy.id, repository, FakeReferenceImageStore())
        assertEquals(EditorStatus.Ready, vm.uiState.value.status)
        vm.selectTool(CellType.GAP)
        vm.tapCell(1, 2)
        vm.save()

        assertNull(vm.uiState.value.dialog)
        assertTrue(vm.uiState.value.isClosed)
        val saved = storedHall()
        assertEquals(listOf("SSS", "SS."), saved.layout!!.rows.map { LayoutPattern.encode(it.cells) })
        assertEquals(legacy.copy(layout = saved.layout), saved)
    }
}
