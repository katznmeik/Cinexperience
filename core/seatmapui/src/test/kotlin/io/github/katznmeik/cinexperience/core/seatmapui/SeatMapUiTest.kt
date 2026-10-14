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
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
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
import org.junit.Assert.assertFalse
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

    /**
     * A hall taller than the map's maximum height, fitted whole: the map keeps that
     * height, and the back row is at its bottom, where a tap finds it without panning.
     */
    @Test
    fun aTallHallFittedWholeShowsItsBackRow() {
        val layout = SeatLayout.fromPatterns(List(30) { "SSS" }, firstSeatId = 1) // back row: 88 89 90
        val clicks = mutableListOf<SeatId>()
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.width(300.dp)) {
                    SeatMap(
                        layout = layout,
                        contentDescription = "Seat map",
                        onSeatClick = { clicks += it },
                        maxHeight = 200.dp,
                        fitWholeHall = true,
                    )
                }
            }
        }
        val map = composeRule.onNodeWithContentDescription("Seat map")
        map.assertHeightIsEqualTo(200.dp)
        map.performTouchInput { click(Offset(centerX, bottom - 6.dp.toPx())) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        assertEquals(listOf(SeatId(89)), clicks)
    }

    /**
     * Selecting a seat only redraws the map (its prepared drawing is kept): the ring
     * appears on the newly selected seat, and the map looks as before once deselected.
     */
    @Test
    fun selectingASeatRedrawsTheRing() {
        val layout = SeatLayout.fromPatterns("SSSSS", firstSeatId = 1)
        var selected by mutableStateOf<SeatId?>(null)
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.width(300.dp)) { SeatMap(layout = layout, contentDescription = "Seat map", selectedSeatId = selected) }
            }
        }
        fun pixels(): IntArray {
            val image = composeRule.onNodeWithContentDescription("Seat map").captureToImage()
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        val none = pixels()
        selected = SeatId(3)
        composeRule.waitForIdle()
        val third = pixels()
        assertFalse("the ring is drawn", none.contentEquals(third))
        selected = SeatId(4)
        composeRule.waitForIdle()
        assertFalse("the ring moves", third.contentEquals(pixels()))
        selected = null
        composeRule.waitForIdle()
        assertTrue("no ring", none.contentEquals(pixels()))
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
        composeRule.onNodeWithContentDescription("Row 10, Seat 2, wheelchair space, right block").performClick()
        composeRule.onNodeWithContentDescription("Row 10, Seat 3, sofa, centre block").performClick()
        val row10 = SampleLayouts.splitHall.rows[9].seatIds
        assertEquals(listOf(row10.last(), row10[4]), chosen)
        // Split rows show a caption per block.
        composeRule.onNodeWithText("Centre").assertExists()
    }

    /** Kant Kino Saal 3: every row is unnamed, so there is no row step; the seats come by number. */
    @Test
    fun seatPickerOffersUnnamedRowsBySeatNumber() {
        val chosen = mutableListOf<SeatId>()
        composeRule.setContent {
            CinexTheme { SeatPicker(seatChoices(SampleLayouts.kantKinoSaal3), selectedSeatId = null, onSeatSelected = { chosen += it }) }
        }
        composeRule.onNodeWithText("Row").assertDoesNotExist()
        composeRule.onNodeWithText("Seats").assertDoesNotExist()
        composeRule.onNodeWithText("Seat").assertExists()
        composeRule.onNodeWithContentDescription("Seat 12, sofa").performClick()
        composeRule.onNodeWithContentDescription("Seat 1, sofa").performClick()
        assertEquals(listOf(SampleLayouts.kantKinoSaal3Seat12, SampleLayouts.kantKinoSaal3.rows[10].seatIds[0]), chosen)
    }

    /** Named rows and unnamed ones in one hall: the unnamed seats are one more chip, "Seats", in their place. */
    @Test
    fun seatPickerMixesNamedRowsAndUnnamedSeats() {
        val mixed = SeatLayout.fromPatterns("SSS", "FF.", ".FF", "SSS", firstSeatId = 1)
            .updateRow(1) { it.copy(label = "-", blockStartNumbers = listOf(3)) }
            .updateRow(2) { it.copy(label = "-") }
        val chosen = mutableListOf<SeatId>()
        var selected by mutableStateOf<SeatId?>(null)
        composeRule.setContent {
            CinexTheme { SeatPicker(seatChoices(mixed), selectedSeatId = selected, onSeatSelected = { chosen += it }) }
        }
        composeRule.onNodeWithContentDescription("Row 1").assertIsSelected()
        composeRule.onNodeWithContentDescription("Row 4").assertExists()
        composeRule.onNodeWithContentDescription("Seats without a row").performClick()
        composeRule.onNodeWithContentDescription("Seats without a row").assertIsSelected()
        composeRule.onNodeWithText("Seats without a row").assertExists() // the heading of the seat step
        composeRule.onNodeWithContentDescription("Seat 3, sofa").performClick()
        assertEquals(listOf(SeatId(4)), chosen)
        // A seat tapped on the map in row 4 shows that row again.
        selected = SeatId(9)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Row 4").assertIsSelected()
        composeRule.onNodeWithContentDescription("Row 4, Seat 2").assertIsSelected()
        // And one in an unnamed row shows the group.
        selected = SeatId(7)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Seats without a row").assertIsSelected()
        composeRule.onNodeWithContentDescription("Seat 2, sofa").assertIsSelected()
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

