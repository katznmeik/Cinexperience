package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
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
     * (top row) and on wheelchair, companion and sofa seats (bottom rows).
     */
    @Test
    fun seatMapStates() {
        val layout = SeatLayout.fromPatterns("SSSS|SSSS", "WCFS|WCFW", "WCF.|.FCW", firstSeatId = 1)
        val tones = listOf(SeatTone.NO_DATA, SeatTone.GREAT, SeatTone.OK, SeatTone.AVOID)
        val visuals = layout.seatIds.withIndex().associate { (index, id) ->
            id to SeatVisual(tones[index % 4], isFavourite = index in setOf(4, 5, 13, 14))
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
}
