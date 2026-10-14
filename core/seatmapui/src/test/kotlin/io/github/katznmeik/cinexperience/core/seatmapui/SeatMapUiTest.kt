package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
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
        composeRule.onNodeWithContentDescription("Row 10").performClick()
        composeRule.onNodeWithContentDescription("Row 10").assertIsSelected()
        composeRule.onNodeWithContentDescription("Row 10, Wheelchair 2, right block").performClick()
        composeRule.onNodeWithContentDescription("Row 10, Seat 3, sofa, centre block").performClick()
        val row10 = SampleLayouts.splitHall.rows[9].seatIds
        assertEquals(listOf(row10.last(), row10[4]), chosen)
        // Split rows show a caption per block.
        composeRule.onNodeWithText("Centre").assertExists()
    }

    @Test
    fun seatPickerFollowsASeatSelectedElsewhere() {
        val choices = seatChoices(SampleLayouts.splitHall)
        var selected by mutableStateOf<SeatId?>(null)
        composeRule.setContent {
            CinexTheme { SeatPicker(choices, selectedSeatId = selected, onSeatSelected = {}) }
        }
        composeRule.onNodeWithContentDescription("Row 1").assertIsSelected()
        selected = SampleLayouts.splitHall.rows[6].seatIds[13] // tapped on the map: row 7, seat 10
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Row 7").assertIsSelected()
        composeRule.onNodeWithContentDescription("Row 7, Seat 10, centre block").assertIsSelected()
    }

    @Test
    fun seatStatesAreAnnounced() {
        val layout = SeatLayout.fromPatterns("SSS", firstSeatId = 1)
        val visuals = mapOf(
            SeatId(1) to SeatVisual(SeatTone.AVOID),
            SeatId(2) to SeatVisual(SeatTone.GREAT, isFavourite = true),
        )
        composeRule.setContent {
            CinexTheme {
                Column {
                    SeatMap(layout = layout, contentDescription = "Seat map", seatVisuals = visuals, selectedSeatId = SeatId(2))
                    SeatPicker(seatChoices(layout), selectedSeatId = null, onSeatSelected = {}, seatVisuals = visuals)
                }
            }
        }
        val map = composeRule.onNodeWithContentDescription("Seat map").fetchSemanticsNode()
        assertEquals("Selected: Row 1, Seat 2, great, favourite", map.config[SemanticsProperties.StateDescription])
        composeRule.onNodeWithContentDescription("Row 1, Seat 1, avoid").assertExists()
        composeRule.onNodeWithContentDescription("Row 1, Seat 2, great, favourite").assertExists()
        composeRule.onNodeWithContentDescription("Row 1, Seat 3, no data").assertExists()
    }
}

