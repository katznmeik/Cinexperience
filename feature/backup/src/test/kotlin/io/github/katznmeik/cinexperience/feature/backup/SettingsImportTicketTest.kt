package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → Data → Import ticket (spec §6.7, §6.10): a row that opens the file
 * picker, locked like the other Data rows while a backup import replaces the data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h1400dp-port-night-xxhdpi")
class SettingsImportTicketTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var picks = 0

    private fun show(backup: BackupUiState = BackupUiState()) {
        val state = SettingsUiState(appVersion = "1.4.0", buildId = "abc1234", backup = backup)
        composeRule.setContent {
            ScreenshotSurface { SettingsScreen(state, onOpenManage = {}, onOpenAllVisits = {}, onImportTicket = { picks++ }) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theRowOpensThePicker() {
        show()
        composeRule.onNodeWithText(IMPORT_TICKET_SUBTITLE).assertExists()
        composeRule.onNodeWithText("Import ticket").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, picks)
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings_import_ticket.png")
    }

    @Test
    fun aBackupImportLocksTheRow() {
        show(BackupUiState(task = BackupTask.Importing(0.4f, ImportSource.FILE)))
        composeRule.onNodeWithText("Import ticket").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, picks)
    }
}
