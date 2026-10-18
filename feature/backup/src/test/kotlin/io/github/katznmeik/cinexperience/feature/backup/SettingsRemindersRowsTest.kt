package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rows of Settings → Reminders (spec §6.7): what they call, and locked during this screen's backup import. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h3600dp-port-night-xxhdpi")
class SettingsRemindersRowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = object : ReminderSettingsActions {
        override fun onSwitchChanged(kind: ReminderKind, on: Boolean) {
            calls += "$kind=$on"
        }

        override fun onLeadTimeChanged(leadTime: ReminderLeadTime) {
            calls += "$leadTime"
        }

        override fun onOpenNotificationSettings() {
            calls += "settings"
        }
    }

    private fun show(reminders: ReminderSettingsState, backup: BackupUiState = BackupUiState()) {
        val state = SettingsUiState(appVersion = "1.4.0", buildId = "abc1234", backup = backup)
        composeRule.setContent {
            ScreenshotSurface { SettingsScreen(state, onOpenManage = {}, onOpenAllVisits = {}, reminders = reminders, reminderActions = actions) }
        }
        composeRule.waitForIdle()
    }

    private fun switch(title: String) = composeRule.onNode(isToggleable() and hasText(title)).performScrollTo()

    @Test
    fun theRowsCallTheirActions() {
        show(
            ReminderSettingsState.OFF.copy(
                settings = ReminderSettings(beforeShowing = true),
                notificationsAllowed = false,
            ),
        )
        switch(ReminderTexts.BEFORE_SHOWING).performClick()
        switch(ReminderTexts.RATE_VISIT).performClick()
        composeRule.onNodeWithText("2 hours").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("1 hour").performClick()
        composeRule.onNodeWithText(ReminderTexts.OPEN_SETTINGS).performScrollTo().performClick()
        assertEquals(listOf("BEFORE_SHOWING=false", "RATE_VISIT=true", "ONE_HOUR", "settings"), calls)
    }

    @Test
    fun theSwitchesWaitForTheStoredSettings() {
        show(ReminderSettingsState(isLoaded = false))
        switch(ReminderTexts.BEFORE_SHOWING).assertIsNotEnabled()
        switch(ReminderTexts.RATE_VISIT).assertIsNotEnabled()
    }

    @Test
    fun aBackupImportOfThisScreenLocksTheRows() {
        show(
            ReminderSettingsState.OFF.copy(settings = ReminderSettings(beforeShowing = true)),
            BackupUiState(task = BackupTask.Importing(0.4f, ImportSource.FILE)),
        )
        switch(ReminderTexts.BEFORE_SHOWING).assertIsNotEnabled().performClick()
        switch(ReminderTexts.RATE_VISIT).assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("3 hours").performScrollTo().performClick()
        assertEquals(emptyList<String>(), calls)
    }
}
