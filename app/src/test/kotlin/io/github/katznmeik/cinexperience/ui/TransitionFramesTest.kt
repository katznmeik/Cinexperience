package io.github.katznmeik.cinexperience.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frame captures of the navigation transitions (spec §7, [CinexTransitions]):
 * forward into a detail, Back out of it (button and predictive back gesture) and a
 * tab switch, each at 0 / 25 / 50 / 75 / 100 % of its duration, with the test
 * clock driven by hand. The frames land in app/build/outputs/transitions (not
 * with the regular screenshots, since they are intermediate states).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TransitionFramesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun showApp() {
        val container = FakeAppContainer(FakeCinexRepository(SampleData.store()))
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CinexTheme { CinexApp(container) }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("build/outputs/transitions/$name.png")
    }

    /** Runs [start] with the clock stopped, then captures the transition at five points. */
    private fun captureTransition(name: String, durationMs: Long = CinexTransitions.DURATION_MS.toLong(), start: () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        start()
        // One frame to start the transition.
        composeRule.mainClock.advanceTimeByFrame()
        capture("${name}_000")
        val step = durationMs / 4
        listOf("025", "050", "075").forEach { pct ->
            composeRule.mainClock.advanceTimeBy(step)
            capture("${name}_$pct")
        }
        composeRule.mainClock.advanceTimeBy(step + 64)
        capture("${name}_100")
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun openHall() {
        captureTransition("open_cinema") { composeRule.onNodeWithText("DELPHI LUX").performClick() }
        captureTransition("open_hall") { composeRule.onNodeWithText("Saal 1").performClick() }
        composeRule.onNodeWithText("SAAL 1").assertExists()
    }

    @Test
    fun forwardAndBackWithTheBackButton() {
        showApp()
        openHall()
        captureTransition("back_hall") { composeRule.onNodeWithContentDescription("Back").performClick() }
        composeRule.onNodeWithText("DELPHI LUX").assertExists()
        captureTransition("back_cinema") { composeRule.onNodeWithContentDescription("Back").performClick() }
        composeRule.onNodeWithText("13 venues", substring = true).assertExists()
    }

    /** The predictive back gesture follows the finger, then finishes from where it was released. */
    @Test
    fun predictiveBackFollowsTheGestureAndFinishes() {
        showApp()
        openHall()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { backDispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.mainClock.advanceTimeByFrame()
        capture("gesture_hall_000")
        listOf(25, 50, 75).forEach { pct ->
            composeRule.runOnUiThread { backDispatcher.dispatchOnBackProgressed(backEvent(pct / 100f)) }
            composeRule.mainClock.advanceTimeByFrame()
            capture("gesture_hall_0$pct")
        }
        captureTransition("gesture_hall_release") { composeRule.runOnUiThread { backDispatcher.onBackPressed() } }
        composeRule.onNodeWithText("DELPHI LUX").assertExists()

        // Back to the list: the bottom bar comes back with it.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { backDispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnUiThread { backDispatcher.dispatchOnBackProgressed(backEvent(0.5f)) }
        composeRule.mainClock.advanceTimeByFrame()
        capture("gesture_cinema_050")
        captureTransition("gesture_cinema_release") { composeRule.runOnUiThread { backDispatcher.onBackPressed() } }
        composeRule.onNodeWithText("13 venues", substring = true).assertExists()
    }

    @Test
    fun tabSwitch() {
        showApp()
        captureTransition("tab_rankings") {
            composeRule.onNodeWithTag(TopLevelDestination.Rankings.testTag).performClick()
        }
    }

    private fun backEvent(progress: Float) = BackEventCompat(
        touchX = 10f + 600f * progress,
        touchY = 1200f,
        progress = progress,
        swipeEdge = BackEventCompat.EDGE_LEFT,
    )
}
