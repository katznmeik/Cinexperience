package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the seat map itself; PNGs land in core/seatmapui/build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class SeatMapScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The visual states M4 will use (colours, favourite, avoid ×) on standard seats
     * (top row), on wheelchair, companion and sofa seats (middle rows) and every
     * colour as a favourite (bottom row; the favourite "avoid" keeps its × badge).
     */
    @Test
    fun seatMapStates() {
        val layout = SeatLayout.fromPatterns("SSSS|SSSS", "WCFS|WCFW", "WCF.|.FCW", "SSSS|....", firstSeatId = 1)
        val tones = listOf(SeatTone.NO_DATA, SeatTone.GREAT, SeatTone.OK, SeatTone.AVOID)
        val visuals = layout.seatIds.withIndex().associate { (index, id) ->
            id to SeatVisual(tones[index % 4], isFavourite = index in setOf(4, 5, 13, 14, 22, 23, 24, 25))
        }
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    SeatMap(
                        layout = layout,
                        contentDescription = "States",
                        screenWidth = 6f,
                        seatVisuals = visuals,
                        selectedSeatId = SeatId(3),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seat_map_states.png")
    }

    /**
     * Seat numbers and the minimum number size: a 22-column hall fitted to a phone's
     * map card shows its numbers; a 30-column hall is too small for them when fitted
     * (its seats show their marks as symbols) and shows them once zoomed in.
     */
    @Test
    fun seatNumbersFittedAndZoomed() {
        val wide = SeatLayout.fromPatterns(List(6) { "SSSSSS|" + "S".repeat(16) + "|SSSSSS" }, firstSeatId = 1)
        val visuals = wide.seatIds.withIndex().associate { (index, id) ->
            id to SeatVisual(listOf(SeatTone.NO_DATA, SeatTone.GREAT, SeatTone.OK, SeatTone.AVOID)[index / 7 % 4])
        }
        val zoomed = SeatMapViewState().apply { userTransform = ViewTransform(scale = 1.4f, offset = Offset(-280f, 60f)) }
        composeRule.setContent {
            CinexTheme {
                Column(
                    Modifier.fillMaxSize().background(CinexTheme.colors.background).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Card {
                        SeatMap(SampleLayouts.splitHall, "22 columns", screenWidth = 16f, maxHeight = 240.dp, modifier = Modifier.fillMaxWidth())
                    }
                    Card { SeatMap(wide, "30 columns", seatVisuals = visuals, maxHeight = 200.dp, modifier = Modifier.fillMaxWidth()) }
                    Card {
                        SeatMap(
                            wide,
                            "30 columns, zoomed in",
                            seatVisuals = visuals,
                            maxHeight = 200.dp,
                            viewState = zoomed,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seat_map_numbers.png")
    }

    /**
     * Seat numbers as Yorck has them (spec §4.3): row 1 as delphi LUX Saal 1 (wheelchair
     * spaces numbered 1, 2 and 12, 13, each with its symbol above the number), row 2
     * right to left starting at 4, row 3 right to left with a fixed "R" (Kant Kino
     * Saal 4, row 8) and row 4 left to right with an "R" (Kant Kino Saal 5, row 1).
     * Wheelchair spaces in every state: the symbol and the ×/★ share the band.
     */
    @Test
    fun yorckNumbering() {
        val layout = SeatLayout.fromPatterns(
            "W.W|SSSSSSSSS|W.W",
            "...SSSSSSSSSS....",
            "...SSSSSSW.......",
            ".......WSSSSSS...",
            firstSeatId = 1,
        ).updateRow(1) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(4)) }
            .updateRow(2) { it.copy(label = "8", numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
            .withFixedLabel(2, 9, "R")
            .withFixedLabel(3, 7, "R")
        val tones = listOf(SeatTone.GREAT, SeatTone.OK, SeatTone.AVOID, SeatTone.NO_DATA)
        val wheelchairs = layout.seatIds.filter { id ->
            layout.positionOf(id)!!.let { (layout.cell(it.row, it.column) as LayoutCell.Seat).kind == SeatKind.WHEELCHAIR }
        }
        val visuals = wheelchairs.withIndex().associate { (index, id) ->
            id to SeatVisual(tones[index % 4], isFavourite = index == 1)
        }
        val labels = layout.labels()
        check(layout.rows[2].seatIds.map { labels.labelOf(it)!!.seat } == listOf("6", "5", "4", "3", "2", "1", "R"))
        composeRule.setContent {
            CinexTheme {
                Column(
                    Modifier.fillMaxSize().background(CinexTheme.colors.background).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Card {
                        SeatMap(layout, "Yorck numbering", screenWidth = 14f, seatVisuals = visuals, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seat_map_yorck_numbering.png")
    }

    /**
     * A narrow screen moved as far as the editor allows (a 10-column hall, a 2-seat
     * screen 5 seats left and right of centre): the "SCREEN" caption stays whole.
     */
    @Test
    fun screenCaptionAtTheGridsEdge() {
        val layout = SeatLayout.fromPatterns(List(5) { "S".repeat(10) }, firstSeatId = 1)
        composeRule.setContent {
            CinexTheme {
                Column(
                    Modifier.fillMaxSize().background(CinexTheme.colors.background).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (offset in listOf(-5f, 5f)) {
                        Card {
                            SeatMap(
                                layout,
                                "Screen $offset",
                                screenWidth = 2f,
                                screenOffset = offset,
                                maxHeight = 260.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seat_map_screen_edge.png")
    }

    /** Like the hall detail's map card: surface, 6 dp side padding. */
    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth().background(CinexTheme.colors.surface).padding(horizontal = 6.dp, vertical = 10.dp)) {
            content()
        }
    }

    /** The legend under the map, on the map card's surface. */
    @Test
    fun seatLegend() {
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    Box(Modifier.padding(16.dp).background(CinexTheme.colors.surface).padding(vertical = 14.dp, horizontal = 10.dp)) {
                        SeatLegend(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seat_legend.png")
    }
}
