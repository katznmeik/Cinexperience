package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
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
 * Settings → Program with a downloaded program (the rows enabled) and its
 * "delete program" dialog at font scales 1, 1.5 and 2, with the TalkBack checks.
 * The font scale is set through the configuration, so the dialog window gets it
 * too; the clock is held, so the dialog's animations can't keep Compose busy.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h3600dp-port-night-xxhdpi")
class SettingsProgramAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, program: ProgramSettingsState) {
        val cinemas = runBlocking { FakeCinexRepository(SampleData.store()).observeCinemaDetails().first() }
        val state = SettingsUiState(appVersion = "1.1.0", buildId = "abc1234", counts = DataCounts.of(cinemas), program = program)
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface { SettingsScreen(state, onOpenManage = {}, onOpenAllVisits = {}) } }
        composeRule.mainClock.advanceTimeBy(2_000)
        if (fontScale != 1f) captureScreenRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    private val downloaded = ProgramSettingsState(isLoaded = true, downloadsOn = true, lastDownload = SampleProgram.FETCHED_AT)

    @Test
    fun programDownloaded() = check("settings_program_on", downloaded)

    @Test
    fun deleteProgramDialog() = check("settings_program_delete", downloaded.copy(confirmingDelete = true))
}
