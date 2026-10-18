package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Add visit with an imported ticket (spec §6.10) at font scales 1, 1.5 and 2 with the
 * TalkBack checks (tall captures): the form with the ticket, the notes, and the seat
 * picker with the booking's seats marked.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h3000dp-port-night-xxhdpi")
class VisitTicketImportAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun check(name: String, state: VisitEditUiState) {
        composeRule.setContent { ScreenshotSurface(fontScale) { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun addVisitWithTicket() = check("add_visit_ticket", VisitImportStates.addWithTicket())

    @Test
    fun addVisitWithNotes() = check("add_visit_ticket_notes", VisitImportStates.addWithNotes())

    @Test
    fun seatPickerWithTheBooking() = check("add_visit_ticket_picker", VisitImportStates.picker())
}
