package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.seatmapui.rememberSeatMapViewState
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.seatmap.editor.EditorCanvas
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutDraft
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorActions
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorEntry
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class SeatMapUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorSteppersUndoAndDiscard() {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val viewModel = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        var closed = false
        composeRule.setContent {
            CinexTheme { LayoutEditorEntry(viewModel, FakeReferenceImageStore(), onClose = { closed = true }) }
        }
        composeRule.onNodeWithText("SAAL 1 · LAYOUT").assertExists()
        composeRule.onNodeWithContentDescription("Increase rows").performClick()
        composeRule.onNodeWithText("11").assertExists()
        composeRule.onNodeWithContentDescription("Undo").performClick()
        composeRule.onNodeWithText("10").assertExists()
        composeRule.onNodeWithContentDescription("Increase screen distance").performClick()
        composeRule.onNodeWithText("3.0 rows").assertExists()
        composeRule.onNodeWithContentDescription("Undo").performClick()

        // The reference panel replaces the row panel.
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Reference screenshot")))
            .performClick()
        composeRule.onNodeWithText("Choose screenshot").assertExists()

        // Nothing changed: Discard closes without asking.
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.waitForIdle()
        assertTrue(closed)
    }

    @Test
    fun referenceModeWithoutALoadedImageDoesNotPaint() {
        val draft = LayoutDraft.start(Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = SampleLayouts.splitHall))
        val taps = mutableListOf<CellPosition>()
        val actions = object : LayoutEditorActions {
            override fun onTapCell(row: Int, column: Int) {
                taps += CellPosition(row, column)
            }
        }
        var referenceMode by mutableStateOf(false)
        composeRule.setContent {
            CinexTheme {
                EditorCanvas(
                    draft = draft,
                    labels = draft.layout.labels(),
                    selectedRow = null,
                    referenceMode = referenceMode,
                    referenceImage = null, // e.g. still loading
                    referenceOpacity = 0.4f,
                    referenceAlignment = null,
                    actions = actions,
                    viewState = rememberSeatMapViewState(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val canvas = composeRule.onNodeWithContentDescription("Layout canvas", substring = true)
        canvas.performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertEquals(1, taps.size) // editing: the tap paints the cell under it

        referenceMode = true
        composeRule.waitForIdle()
        canvas.performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertEquals(1, taps.size) // reference mode: nothing painted
    }

    @Test
    fun discardWithChangesAsksFirst() {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val viewModel = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        var closed = false
        composeRule.setContent {
            CinexTheme { LayoutEditorEntry(viewModel, FakeReferenceImageStore(), onClose = { closed = true }) }
        }
        composeRule.onNodeWithContentDescription("Increase columns").performClick()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithText("Discard changes?").assertExists()
        composeRule.onNodeWithText("Keep editing").performClick()
        assertTrue(!closed)
        composeRule.onNodeWithText("23").assertExists()
    }
}
