package io.github.katznmeik.cinexperience.feature.tickets

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the ticket screen (spec §6.11) with invented data, phone-sized,
 * dark theme: the QR code, a removed one, one that isn't stored, no ticket. PNGs
 * land in feature/tickets/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TicketScreenshotTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var backs = 0

    private fun capture(name: String, state: TicketContent) {
        composeRule.setContent { ScreenshotSurface { TicketScreen(state, onBack = { backs++ }) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun withQrCode() {
        capture("ticket_qr", TicketScreenStates.withQrCode())
        composeRule.onNodeWithContentDescription(QR_CODE_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription("Booking number, T E S T 1 2 3").assertExists()
        composeRule.onNodeWithContentDescription("Row 7, Seat 10, your seat").assertExists()
        composeRule.onNodeWithContentDescription("Row 7, Seat 11").assertExists()
        composeRule.onNodeWithText("Saturday 17 Oct · 20:15").assertExists()
        composeRule.onNodeWithText("delphi LUX · Saal 4").assertExists()
    }

    /** In landscape the height limits the QR code: it fits on the screen whole, centred, without scrolling. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-land-night-xxhdpi")
    fun withQrCodeInLandscape() {
        capture("ticket_qr_landscape", TicketScreenStates.withQrCode())
        val root = composeRule.onRoot().getBoundsInRoot()
        val qr = composeRule.onNodeWithContentDescription(QR_CODE_DESCRIPTION).getBoundsInRoot()
        assertTrue("the QR code ends above the screen's bottom: $qr in $root", qr.bottom <= root.bottom)
        assertEquals("square", (qr.right - qr.left).value, (qr.bottom - qr.top).value, 1f)
        assertEquals("centred", ((qr.left + qr.right) / 2).value, ((root.left + root.right) / 2).value, 1f)
    }

    @Test
    fun qrCodeRemovedAfterTheShowing() {
        capture("ticket_qr_removed", TicketScreenStates.qrCodeRemoved())
        composeRule.onNodeWithContentDescription(QR_CODE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText(QR_REMOVED_NOTE).assertExists()
        composeRule.onNodeWithContentDescription("Booking number, T E S T 1 2 3").assertExists()
    }

    @Test
    fun qrCodeNotStored() {
        capture("ticket_no_qr", TicketScreenStates.qrCodeNotStored())
        composeRule.onNodeWithContentDescription(QR_CODE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText(QR_NOT_STORED_NOTE).assertExists()
    }

    /** The seats couldn't be read from the ticket: the visit's own seat shows as "Your seat". */
    @Test
    fun withoutSeats() {
        capture("ticket_no_seats", TicketScreenStates.withoutSeats())
        composeRule.onNodeWithContentDescription("Row 7, Seat 10, your seat").assertExists()
        composeRule.onNodeWithText("Seat").assertExists()
        composeRule.onNodeWithText("Seats").assertDoesNotExist()
    }

    @Test
    fun notFoundWithBack() {
        capture("ticket_not_found", TicketContent.NotFound)
        composeRule.onNodeWithText(TICKET_NOT_FOUND).assertExists()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
