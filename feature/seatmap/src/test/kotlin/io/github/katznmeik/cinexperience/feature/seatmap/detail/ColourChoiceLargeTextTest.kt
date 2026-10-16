package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.feature.seatmap.RatedHallFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The seat colour buttons (Auto · Great · OK · Avoid) stack into two rows of two
 * above the app's one large-text threshold (font scale 1.3), like every other
 * side-by-side layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class ColourChoiceLargeTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(fontScale: Float) {
        val repository = FakeCinexRepository(RatedHallFixture.store())
        val vm = HallDetailViewModel(RatedHallFixture.HALL, repository, repository)
        vm.onSeatClick(RatedHallFixture.favourite)
        val state = runBlocking { vm.uiState.first { it !is HallDetailUiState.Loading } }
        composeRule.setContent {
            ScreenshotSurface(fontScale) {
                HallDetailScreen(state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {}, onSeatClick = {}, onSeatChosen = {})
            }
        }
        composeRule.waitForIdle()
    }

    private fun top(description: String): Float =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun oneRowUpToTheThreshold() {
        show(fontScale = 1.3f)
        assertEquals(top("Seat colour: Auto, from ratings"), top("Seat colour: Avoid"))
    }

    @Test
    fun twoRowsAboveTheThreshold() {
        show(fontScale = 1.4f)
        val auto = top("Seat colour: Auto, from ratings")
        assertEquals(auto, top("Seat colour: Great"))
        assertTrue("OK starts the second row", top("Seat colour: OK") > auto)
        assertEquals(top("Seat colour: OK"), top("Seat colour: Avoid"))
    }
}
