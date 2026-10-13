package io.github.katznmeik.cinexperience.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the three tab screens, phone-sized (412×915 dp, xxhdpi) in the
 * dark theme. Record with `./gradlew recordRoborazziDebug`; PNGs land in
 * app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TabScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showApp() {
        composeRule.setContent {
            CinexTheme { CinexApp() }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun cinemasTab() {
        showApp()
        capture("tab_cinemas")
    }

    @Test
    fun rankingsTab() {
        showApp()
        composeRule.onNodeWithTag(TopLevelDestination.Rankings.testTag).performClick()
        capture("tab_rankings")
    }

    @Test
    fun settingsTab() {
        showApp()
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        capture("tab_settings")
    }
}

internal const val PHONE_QUALIFIERS = "w412dp-h915dp-port-night-xxhdpi"
