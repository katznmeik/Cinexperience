package io.github.katznmeik.cinexperience.feature.tickets.importing

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The import screen at large font scales, with the basic TalkBack checks of [assertAccessible]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TicketImportAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, state: TicketImportState) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface(fontScale) { TicketImportScreen(state, onBack = {}) } }
        composeRule.mainClock.advanceTimeBy(600)
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun reading() = check("ticket_import_reading", TicketImportState.Reading)

    @Test
    fun notATicket() = check("ticket_import_not_a_ticket", TicketImportState.Failed(TicketImportError.NOT_A_TICKET))

    @Test
    fun checkFailed() = check("ticket_import_check_failed", TicketImportState.Failed(TicketImportError.CHECK_FAILED))
}
