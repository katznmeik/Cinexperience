package io.github.katznmeik.cinexperience.ui

import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
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

/**
 * The ticket screen in the app (spec §6.11): opened from the Next visit card (§6.1)
 * and from the visit's Ticket section (§6.4); while it shows the QR code the window
 * is at full brightness and kept on, and leaving it (Back button or system Back)
 * restores both. The container's clock stands at 15 Oct 2026, 10:00 in Berlin; the
 * invented visit ([SampleTickets]) is on Saturday 17 Oct at 20:15.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TicketNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val window get() = composeRule.activity.window
    private val bright get() = window.attributes.screenBrightness == BRIGHTNESS_OVERRIDE_FULL
    private val keptOn get() = window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0

    private fun showApp() {
        val container = FakeAppContainer(FakeCinexRepository(SampleTickets.store()))
        composeRule.setContent { CinexTheme { CinexApp(container) } }
        composeRule.waitForIdle()
    }

    private fun assertTicketShown() {
        composeRule.onNodeWithText("TICKET").assertExists()
        composeRule.onNodeWithContentDescription("Ticket QR code").assertExists()
        assertTrue("full brightness while the QR code is shown", bright)
        assertTrue("screen kept on while the QR code is shown", keptOn)
    }

    private fun assertRestored() {
        assertEquals(BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
        assertFalse(keptOn)
    }

    @Test
    fun theNextVisitCardOpensTheTicketAndBackRestoresTheScreen() {
        showApp()
        composeRule.onNodeWithContentDescription("Next visit:", substring = true).assertExists()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/tab_cinemas_next_visit.png")
        assertFalse(bright)

        composeRule.onNodeWithText("Show ticket").performClick()
        composeRule.waitForIdle()
        assertTicketShown()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Next visit:", substring = true).assertExists()
        assertRestored()
    }

    @Test
    fun systemBackFromTheTicketRestoresTheScreen() {
        showApp()
        composeRule.onNodeWithText("Show ticket").performClick()
        composeRule.waitForIdle()
        assertTicketShown()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Next visit:", substring = true).assertExists()
        assertRestored()
    }

    @Test
    fun theCardOpensTheVisitWhoseTicketSectionOpensTheTicket() {
        showApp()
        composeRule.onNodeWithContentDescription("Next visit:", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("EDIT VISIT").assertExists()
        composeRule.onNodeWithContentDescription("Start time, 20:15").assertExists()

        composeRule.onNodeWithText("Show ticket").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTicketShown()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("EDIT VISIT").assertExists()
        assertRestored()
    }
}
