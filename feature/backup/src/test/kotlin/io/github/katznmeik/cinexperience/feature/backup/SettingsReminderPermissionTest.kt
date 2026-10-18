package io.github.katznmeik.cinexperience.feature.backup

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → Reminders with Android's notification permission (spec §6.7, §11.3
 * rule 1), through the real permission request (Robolectric records it, the test
 * answers it): granted, the switch goes on; refused, it stays off and the note with
 * the button to Android's notification settings is shown; allowed there, the note goes
 * when the screen resumes. Switching off needs no permission.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h3600dp-port-night-xxhdpi")
class SettingsReminderPermissionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val application = shadowOf(RuntimeEnvironment.getApplication())
    private val store = FakeReminderSettingsStore()
    private val backup = FakeBackupRepository()

    private fun show() {
        val settings = SettingsViewModel(FakeCinexRepository(SampleData.store()), backup, FakeProgramRepository(), "1.4.0", "abc1234")
        val reminders = ReminderSettingsViewModel(store, backup.isImporting, SavedStateHandle())
        composeRule.setContent { CinexTheme { SettingsEntry(settings, reminders, onOpenManage = {}, onOpenAllVisits = {}) } }
        composeRule.waitForIdle()
    }

    private fun switch(title: String): SemanticsNodeInteraction = composeRule.onNode(isToggleable() and hasText(title)).performScrollTo()

    private fun beforeShowing() = switch(ReminderTexts.BEFORE_SHOWING)

    private fun rateVisit() = switch(ReminderTexts.RATE_VISIT)

    /** Answers the permission request the screen made, as Android would after the dialog. */
    private fun answer(granted: Boolean) {
        val request = checkNotNull(shadowOf(composeRule.activity).lastRequestedPermission) { "no permission request" }
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
        if (granted) application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        composeRule.runOnUiThread {
            @Suppress("DEPRECATION")
            composeRule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withThePermissionGrantedTheSwitchGoesOnAtOnce() {
        application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        beforeShowing().assertIsOff().performClick()
        composeRule.waitForIdle()
        assertNull("not asked", shadowOf(composeRule.activity).lastRequestedPermission)
        beforeShowing().assertIsOn()
        assertEquals(ReminderSettings(beforeShowing = true), store.current.value)
        // The lead time chips appear with it.
        composeRule.onNodeWithText("3 hours").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(ReminderLeadTime.THREE_HOURS, store.current.value.leadTime)
        // Off needs nothing either; the chips go.
        beforeShowing().performClick()
        composeRule.waitForIdle()
        assertEquals(ReminderSettings(leadTime = ReminderLeadTime.THREE_HOURS), store.current.value)
        composeRule.onNodeWithText("3 hours").assertDoesNotExist()
    }

    @Test
    fun grantedInTheDialogTheSwitchGoesOn() {
        application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        rateVisit().performClick()
        composeRule.waitForIdle()
        assertEquals("not before the answer", ReminderSettings(), store.current.value)
        answer(granted = true)
        rateVisit().assertIsOn()
        assertEquals(ReminderSettings(rateAfterVisit = true), store.current.value)
        composeRule.onNodeWithText(ReminderTexts.PERMISSION_NOTE).assertDoesNotExist()
    }

    @Test
    fun refusedTheSwitchStaysOffAndTheNoteOpensAndroidsSettings() {
        application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        beforeShowing().performClick()
        composeRule.waitForIdle()
        answer(granted = false)
        beforeShowing().assertIsOff()
        assertEquals(ReminderSettings(), store.current.value)
        composeRule.onNodeWithText(ReminderTexts.PERMISSION_NOTE).performScrollTo().assertExists()

        composeRule.onNodeWithText(ReminderTexts.OPEN_SETTINGS).performScrollTo().performClick()
        val opened = checkNotNull(application.nextStartedActivity)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(composeRule.activity.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        // Allowed in Android's settings: back in the app, the note is gone and the switch can go on.
        application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ReminderTexts.PERMISSION_NOTE).assertDoesNotExist()
        beforeShowing().performClick()
        composeRule.waitForIdle()
        assertEquals(ReminderSettings(beforeShowing = true), store.current.value)
    }

    @Test
    fun aRevokedPermissionShowsTheNoteWithTheSwitchOn() {
        store.current.value = ReminderSettings(beforeShowing = true, rateAfterVisit = true)
        application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        beforeShowing().assertIsOn()
        rateVisit().assertIsOn()
        composeRule.onNodeWithText(ReminderTexts.PERMISSION_NOTE).performScrollTo().assertExists()
        // Switching both off asks nothing, and the note goes.
        beforeShowing().performClick()
        rateVisit().performClick()
        composeRule.waitForIdle()
        assertNull(shadowOf(composeRule.activity).lastRequestedPermission)
        assertEquals(ReminderSettings(), store.current.value)
        composeRule.onNodeWithText(ReminderTexts.PERMISSION_NOTE).assertDoesNotExist()
    }

    @Test
    fun nothingIsWrittenWhileABackupImportRuns() {
        // Started in another window of the app (this one would be covered by the lock).
        application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        backup.isImporting.value = true
        show()
        beforeShowing().performClick()
        composeRule.waitForIdle()
        assertEquals(ReminderSettings(), store.current.value)
    }
}
