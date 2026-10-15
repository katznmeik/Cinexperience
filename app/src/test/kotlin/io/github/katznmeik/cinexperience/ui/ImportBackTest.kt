package io.github.katznmeik.cinexperience.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.katznmeik.cinexperience.core.data.backup.BackupCounts
import io.github.katznmeik.cinexperience.core.data.backup.SafetyCopy
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Back while an import replaces the data (spec §8: nothing else may write
 * meanwhile): Settings blocks it until the import has finished. The import is
 * started through the UI (Restore safety copy → preview → Replace), because the
 * Back handler follows the Settings task, not the repository's `isImporting`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class ImportBackTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private val counts = BackupCounts(cinemas = 13, halls = 36, visits = 27, overrides = 4, photos = 9, referenceImages = 2)
    private val backup = FakeBackupRepository(safetyCopies = listOf(SafetyCopy(1, Instant.parse("2026-10-14T07:12:00Z"), counts)))

    private fun showSettings() {
        val container = FakeAppContainer(FakeCinexRepository(SampleData.freshInstallStore()), backup)
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CinexTheme { CinexApp(container) }
        }
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()
    }

    private fun pressBack() {
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun backIsBlockedWhileAnImportRuns() {
        showSettings()
        composeRule.onNodeWithText("Restore safety copy").performScrollTo().performClick()
        composeRule.onNodeWithText("13 cinemas · 27 visits · 9 photos").performClick() // the copy: prepare
        composeRule.onNodeWithText("Restore safety copy?").assertExists() // the preview

        // Hold the commit mid-way.
        val gate = CompletableDeferred<Unit>()
        backup.gate = gate
        composeRule.onNodeWithText("Replace").performClick()
        composeRule.onNodeWithText("Restoring… 50%").assertExists()
        assertTrue(backup.isImporting.value)

        pressBack()
        pressBack()

        // Still on Settings, still importing.
        composeRule.onNodeWithText("Restoring… 50%").assertExists()
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()
        assertTrue(backup.isImporting.value)
        assertFalse(gate.isCompleted)
        assertEquals(1, backup.committed.size)

        gate.complete(Unit)
        composeRule.waitForIdle()
        assertFalse(backup.isImporting.value)
        assertEquals(1, backup.committed.size)

        // Once it has finished, Back works again: back to the start tab.
        pressBack()
        composeRule.onNodeWithText("13 venues · 0 visits").assertExists()
    }
}
