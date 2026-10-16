package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorEntry
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The editor's "Remove seats with visits?" dialog (spec §6.5), clicked through:
 * "Keep editing" saves nothing and stays in the editor, "Save anyway" saves and
 * closes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class RemoveSeatsDialogUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Three rows of four seats (ids 1–12); seat 12 (row 3, seat 4) has a visit. */
    private val hall = Hall(
        id = HALL,
        cinemaId = 1,
        name = "Saal 1",
        layout = SeatLayout.fromPatterns("SSSS", "SSSS", "SSSS", firstSeatId = 1),
        screenWidth = 4f,
        screenDistance = 2f,
    )
    private val visit = Visit(id = 1, hallId = HALL, date = LocalDate.of(2026, 9, 1), seatId = SeatId(12))
    private val repository = FakeCinexRepository(
        FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall), visits = listOf(visit)),
    )
    private val viewModel = LayoutEditorViewModel(HALL, repository, FakeReferenceImageStore())
    private var closed = 0

    private fun storedHall() = repository.store.value.halls.single { it.id == HALL }

    private fun openEditorAndRemoveTheLastRow() {
        composeRule.setContent {
            CinexTheme { LayoutEditorEntry(viewModel, FakeReferenceImageStore(), onClose = { closed++ }) }
        }
        composeRule.onNodeWithContentDescription("Decrease rows").performClick()
        assertEquals(2, viewModel.uiState.value.draft!!.rowCount)
    }

    private fun saveAndExpectTheWarning() {
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Remove seats with visits?").assertIsDisplayed()
        composeRule.onNodeWithText("Row 3, Seat 4", substring = true).assertIsDisplayed()
    }

    @Test
    fun keepEditingSavesNothing() {
        openEditorAndRemoveTheLastRow()
        saveAndExpectTheWarning()

        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remove seats with visits?").assertDoesNotExist()
        assertNull(viewModel.uiState.value.dialog)
        assertEquals(0, closed)
        // Still editing the shorter grid; the stored hall is untouched.
        assertEquals(2, viewModel.uiState.value.draft!!.rowCount)
        assertEquals(hall, storedHall())
        assertEquals(SeatId(12), repository.store.value.visits.single().seatId)
    }

    @Test
    fun saveAnywaySavesAndCloses() {
        openEditorAndRemoveTheLastRow()
        saveAndExpectTheWarning()

        composeRule.onNodeWithText("Save anyway").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remove seats with visits?").assertDoesNotExist()
        assertEquals(1, closed)
        assertEquals(2, storedHall().layout!!.rowCount)
        // The visit is kept; its seat is gone from the layout.
        assertEquals(visit, repository.store.value.visits.single())
        assertFalse(SeatId(12) in storedHall().layout!!.seatIds)
    }

    @Test
    fun keepEditingThenSaveAnyway() {
        openEditorAndRemoveTheLastRow()
        saveAndExpectTheWarning()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.waitForIdle()
        assertEquals(0, closed)

        // The warning comes again on the next save.
        saveAndExpectTheWarning()
        composeRule.onNodeWithText("Save anyway").performClick()
        composeRule.waitForIdle()
        assertEquals(1, closed)
        assertEquals(2, storedHall().layout!!.rowCount)
    }

    private companion object {
        const val HALL = 1L
    }
}
