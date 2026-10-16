package io.github.katznmeik.cinexperience.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back while an export runs: Settings blocks it, so the export isn't cancelled
 * silently with the Settings entry; it finishes, and Back works again afterwards.
 * The export is started through the UI (Export backup → file picker), because the
 * Back handler follows the Settings task.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class ExportBackTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private val backup = FakeBackupRepository()
    private val destination = "content://documents/cinexperience-backup.zip"

    /** Answers every launched picker at once with [destination], like a user picking a file. */
    private val pickers = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse(destination)))
            }
        }
    }

    private fun showSettings() {
        val container = FakeAppContainer(FakeCinexRepository(SampleData.freshInstallStore()), backup)
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickers) {
                CinexTheme { CinexApp(container) }
            }
        }
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()
    }

    private fun pressBack() {
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun backIsBlockedWhileAnExportRuns() {
        showSettings()

        // Hold the export mid-way.
        val gate = CompletableDeferred<Unit>()
        backup.gate = gate
        composeRule.onNodeWithText("Export backup").performClick()
        composeRule.onNodeWithText("Exporting… 50%").assertExists()
        assertEquals(listOf(destination), backup.exportedTo)

        pressBack()
        pressBack()

        // Still on Settings, still exporting.
        composeRule.onNodeWithText("Exporting… 50%").assertExists()
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()
        assertFalse(gate.isCompleted)

        gate.complete(Unit)
        composeRule.waitForIdle()
        // The export finished: it is recorded and no longer shown as running.
        assertNotNull(backup.lastExport.value)
        composeRule.onNodeWithText("Exporting… 50%").assertDoesNotExist()
        assertEquals(listOf(destination), backup.exportedTo)

        // Once it has finished, Back works again: back to the start tab.
        pressBack()
        composeRule.onNodeWithText("13 venues · 0 visits").assertExists()
    }
}
