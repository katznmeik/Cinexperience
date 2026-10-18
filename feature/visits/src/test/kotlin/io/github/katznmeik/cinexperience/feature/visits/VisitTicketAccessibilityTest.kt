package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditDialog
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditUiState
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The v1.4 parts of the visit screens at font scales 1, 1.5 and 2 with the
 * TalkBack checks: the form with a start time and a ticket, All visits with the
 * Upcoming section (tall captures), the time picker and the "Remove ticket"
 * confirmation (like [VisitDialogsAccessibilityTest]: the font scale comes from the
 * configuration, the clock is held, the screen captures include every window).
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h3000dp-port-night-xxhdpi")
class VisitTicketAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun check(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface(fontScale) { content() } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    /** Shows [state], then [open]s a window on it (a click, or nothing when [state] has a dialog). */
    private fun checkWindow(name: String, state: VisitEditUiState, allowRepeated: Set<String> = emptySet(), open: () -> Unit = {}) {
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) } }
        composeRule.mainClock.advanceTimeBy(2_000)
        open()
        composeRule.mainClock.advanceTimeBy(2_000)
        if (fontScale != 1f) captureScreenRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible(allowRepeated = allowRepeated)
    }

    @Test
    fun editVisitWithTicket() {
        val state = VisitTicketStates.editWithTicket()
        check("edit_visit_ticket") { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) }
    }

    @Test
    fun allVisitsWithUpcoming() {
        val state = VisitTicketStates.allVisitsWithUpcoming()
        check("all_visits_upcoming") { AllVisitsScreen(state, onBack = {}, onOpenVisit = {}) }
    }

    @Test
    fun timePicker() = checkWindow("visit_time_picker", VisitTicketStates.editWithTicket()) {
        composeRule.onNode(hasContentDescription("Start time, ", substring = true)).performClick()
    }

    @Test
    fun removeTicketDialog() =
        checkWindow("visit_remove_ticket_dialog", VisitTicketStates.editWithTicket().copy(dialog = VisitEditDialog.ConfirmRemoveTicket))
}
