package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.data.backup.BackupCounts
import io.github.katznmeik.cinexperience.core.data.backup.ImportPreview
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.data.backup.LastExport
import io.github.katznmeik.cinexperience.core.data.backup.SafetyCopy
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
import java.time.Instant

/**
 * Settings and its backup dialogs at large font scales (Android's non-linear scaling; tall captures; the
 * dialogs are captured with every window), plus the basic TalkBack checks of
 * [assertAccessible].
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class SettingsAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }

        private val EXPORTED_AT: Instant = Instant.parse("2026-10-03T12:05:00Z")
        private val COUNTS = BackupCounts(cinemas = 13, halls = 36, visits = 27, overrides = 4, photos = 9, referenceImages = 2)
        private val COPIES = listOf(
            SafetyCopy(2, Instant.parse("2026-10-14T07:12:00Z"), COUNTS),
            SafetyCopy(1, Instant.parse("2026-10-01T16:40:00Z"), COUNTS.copy(visits = 19, photos = 4)),
        )
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, backup: BackupUiState) {
        val cinemas = runBlocking { FakeCinexRepository(SampleData.store()).observeCinemaDetails().first() }
        val state = SettingsUiState(appVersion = "1.0.0", buildId = "abc1234", counts = DataCounts.of(cinemas), backup = backup)
        // Through the configuration (not ScreenshotSurface), so the dialog windows get the font scale too.
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.setContent { ScreenshotSurface { SettingsScreen(state, onOpenManage = {}, onOpenAllVisits = {}) } }
        composeRule.waitForIdle()
        if (fontScale != 1f) captureScreenRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun settings() = check("settings", BackupUiState(lastExport = LastExport(EXPORTED_AT, COUNTS), safetyCopies = COPIES))

    @Test
    fun importPreview() = check(
        "settings_import_preview",
        BackupUiState(
            lastExport = LastExport(EXPORTED_AT, COUNTS),
            preview = ImportPreview("p", ImportSource.FILE, EXPORTED_AT, "1.0.0", 1, COUNTS),
        ),
    )

    @Test
    fun safetyCopyChoice() = check(
        "settings_safety_copy_choice",
        BackupUiState(lastExport = LastExport(EXPORTED_AT, COUNTS), safetyCopies = COPIES, choosingSafetyCopy = true),
    )
}
