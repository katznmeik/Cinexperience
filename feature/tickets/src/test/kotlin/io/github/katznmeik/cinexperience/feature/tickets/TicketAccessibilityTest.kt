package io.github.katznmeik.cinexperience.feature.tickets

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ticket screen at large font scales (tall captures), plus the basic TalkBack
 * checks of [assertAccessible]: the QR code is announced as "Ticket QR code", the
 * booking number letter by letter, the visit's own seat as "your seat".
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class TicketAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun check(name: String, state: TicketContent) {
        composeRule.setContent { ScreenshotSurface(fontScale) { TicketScreen(state, onBack = {}) } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun withQrCode() = check("ticket_qr", TicketScreenStates.withQrCode())

    @Test
    fun qrCodeRemoved() = check("ticket_qr_removed", TicketScreenStates.qrCodeRemoved())

    @Test
    fun qrCodeNotStored() = check("ticket_no_qr", TicketScreenStates.qrCodeNotStored())

    @Test
    fun notFound() = check("ticket_not_found", TicketContent.NotFound)
}
