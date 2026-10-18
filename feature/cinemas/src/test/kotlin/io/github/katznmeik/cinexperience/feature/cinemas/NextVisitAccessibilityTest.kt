package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cinema list with the Next visit card (spec §6.1) at large font scales, with the TalkBack checks. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class NextVisitAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun cinemasWithNextVisit() {
        val store = SampleTickets.store()
        val state = NextVisitScreenStates.list(store)
        val next = NextVisitScreenStates.nextVisit(store)
        composeRule.setContent {
            ScreenshotSurface(fontScale) {
                CinemasScreen(state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {}, nextVisit = next)
            }
        }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/cinemas_next_visit_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }
}
