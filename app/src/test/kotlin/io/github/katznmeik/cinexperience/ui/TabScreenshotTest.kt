package io.github.katznmeik.cinexperience.ui

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.di.AppContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** App container backed by the in-memory fake repositories. */
private class FakeAppContainer(repository: FakeCinexRepository) : AppContainer {
    override val applicationContext: Context = RuntimeEnvironment.getApplication()
    override val appVersion: String = "0.1.0"
    override val cinemaRepository = repository
    override val hallRepository = repository
    override val visitRepository = repository
    override val seatOverrideRepository = repository
}

/**
 * Screenshots of the three tab screens through the real navigation graph,
 * phone-sized (412×915 dp, xxhdpi) in the dark theme, with the preloaded cinemas
 * as after a fresh install (no visits). Record with `./gradlew recordRoborazziDebug`;
 * PNGs land in app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TabScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showApp() {
        val container = FakeAppContainer(FakeCinexRepository(SampleData.freshInstallStore()))
        composeRule.setContent {
            CinexTheme { CinexApp(container) }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun cinemasTab() {
        showApp()
        composeRule.onNodeWithText("13 venues · 0 visits").assertExists()
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
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()
        capture("tab_settings")
    }

    @Test
    fun cinemaToHallNavigation() {
        showApp()
        composeRule.onNodeWithText("KANT KINO").performClick()
        composeRule.onNodeWithText("Saal 3").performClick()
        composeRule.onNodeWithText("Seat map coming in M4").assertExists()
        composeRule.onNodeWithText("Kant Kino").assertExists()
        capture("hall_detail_placeholder")
    }
}
