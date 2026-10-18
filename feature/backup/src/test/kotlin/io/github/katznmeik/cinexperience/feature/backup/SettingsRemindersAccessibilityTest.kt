package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → Reminders (spec §6.7) at font scales 1, 1.5 and 2, with the TalkBack
 * checks: both off, both on with the lead time chips, and the note after the
 * notification permission was refused. PNGs `settings_reminders_<state>.png` (font 1)
 * and `…_font_150/200.png` in feature/backup/build/outputs/roborazzi.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h3600dp-port-night-xxhdpi")
class SettingsRemindersAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, reminders: ReminderSettingsState, visibleText: String) {
        val cinemas = runBlocking { FakeCinexRepository(SampleData.store()).observeCinemaDetails().first() }
        val state = SettingsUiState(appVersion = "1.4.0", buildId = "abc1234", counts = DataCounts.of(cinemas))
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.setContent {
            ScreenshotSurface { SettingsScreen(state, onOpenManage = {}, onOpenAllVisits = {}, reminders = reminders) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(visibleText).performScrollTo().assertExists()
        val suffix = if (fontScale == 1f) "" else "_${fontScaleSuffix(fontScale)}"
        captureScreenRoboImage("build/outputs/roborazzi/settings_reminders_$name$suffix.png")
        composeRule.assertAccessible()
    }

    @Test
    fun bothOff() = check("off", ReminderSettingsState.OFF, ReminderTexts.RATE_VISIT_SUBTITLE)

    @Test
    fun bothOnWithTheLeadTime() = check(
        "on",
        ReminderSettingsState.OFF.copy(
            settings = ReminderSettings(beforeShowing = true, leadTime = ReminderLeadTime.ONE_HOUR, rateAfterVisit = true),
        ),
        ReminderTexts.LEAD_TIME_LABEL,
    )

    @Test
    fun permissionRefused() = check(
        "permission",
        ReminderSettingsState.OFF.copy(notificationsAllowed = false, permissionRefused = true),
        ReminderTexts.OPEN_SETTINGS,
    )
}
