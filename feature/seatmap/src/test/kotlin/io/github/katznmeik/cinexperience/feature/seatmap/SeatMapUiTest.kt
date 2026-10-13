package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorEntry
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
import io.github.katznmeik.cinexperience.feature.seatmap.map.CHOOSE_BY_LABEL_ACTION
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatMap
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatPicker
import io.github.katznmeik.cinexperience.feature.seatmap.map.seatChoices
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
    fun tappingTheMapReportsTheSeatUnderTheFinger() {
        val layout = SeatLayout.fromPatterns("SSSSS", firstSeatId = 1)
        val clicks = mutableListOf<SeatId>()
        var pickerRequested = false
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.width(300.dp)) {
                    SeatMap(
                        layout = layout,
                        contentDescription = "Seat map of Saal 1",
                        onSeatClick = { clicks += it },
                        onChooseSeatByLabel = { pickerRequested = true },
                    )
                }
            }
        }
        val map = composeRule.onNodeWithContentDescription("Seat map of Saal 1")
        // The single row is drawn at the bottom of the fitted map, centred.
        map.performTouchInput { click(Offset(centerX, bottom - 20.dp.toPx())) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        assertEquals(listOf(SeatId(3)), clicks)

        // Screen readers get a custom action instead of tapping.
        val action = map.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals(CHOOSE_BY_LABEL_ACTION, action.label)
        composeRule.runOnIdle { action.action() }
        assertTrue(pickerRequested)
    }

    @Test
    fun seatPickerChoosesByRowAndNumber() {
        val choices = seatChoices(SampleLayouts.splitHall)
        val chosen = mutableListOf<SeatId>()
        composeRule.setContent {
            CinexTheme { SeatPicker(choices, selectedSeatId = null, onSeatSelected = { chosen += it }) }
        }
        composeRule.onNodeWithContentDescription("Row J").performClick()
        composeRule.onNodeWithContentDescription("Row J").assertIsSelected()
        composeRule.onNodeWithContentDescription("Row J, Wheelchair 2").performClick()
        composeRule.onNodeWithContentDescription("Row J, Seat 3, sofa").performClick()
        val rowJ = SampleLayouts.splitHall.rows[9].seatIds
        assertEquals(listOf(rowJ.last(), rowJ[4]), chosen)
    }

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
