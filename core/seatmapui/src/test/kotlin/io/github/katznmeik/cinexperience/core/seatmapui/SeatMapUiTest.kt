package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
        composeRule.onNodeWithContentDescription("Row J").performClick()
        composeRule.onNodeWithContentDescription("Row J").assertIsSelected()
        composeRule.onNodeWithContentDescription("Row J, Wheelchair 2").performClick()
        composeRule.onNodeWithContentDescription("Row J, Seat 3, sofa").performClick()
        val rowJ = SampleLayouts.splitHall.rows[9].seatIds
        assertEquals(listOf(rowJ.last(), rowJ[4]), chosen)
    }
}
