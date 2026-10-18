package io.github.katznmeik.cinexperience.feature.tickets.importing

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the import screen (spec §6.10), phone-sized, dark theme: "Reading
 * ticket…" and the errors, each with a way back. PNGs land in
 * feature/tickets/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TicketImportScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0

    private fun capture(name: String, state: TicketImportState) {
        // The progress bar runs forever: the clock is held so Compose can go idle.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface { TicketImportScreen(state, onBack = { backs++ }) } }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun reading() {
        capture("ticket_import_reading", TicketImportState.Reading)
        composeRule.onNodeWithText(READING_TICKET).assertExists()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals("Back works while the file is read", 1, backs)
    }

    @Test
    fun notATicket() {
        capture("ticket_import_not_a_ticket", TicketImportState.Failed(TicketImportError.NOT_A_TICKET))
        composeRule.onNodeWithText(TicketImportError.NOT_A_TICKET.message).assertExists()
        composeRule.onNodeWithText(NOTHING_SAVED).assertExists()
        composeRule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun cantOpen() {
        capture("ticket_import_open_failed", TicketImportState.Failed(TicketImportError.OPEN_FAILED))
        composeRule.onNodeWithText(TicketImportError.OPEN_FAILED.message).assertExists()
    }

    @Test
    fun tooLarge() {
        capture("ticket_import_too_large", TicketImportState.Failed(TicketImportError.TOO_LARGE))
        composeRule.onNodeWithText(TicketImportError.TOO_LARGE.message).assertExists()
    }
}
