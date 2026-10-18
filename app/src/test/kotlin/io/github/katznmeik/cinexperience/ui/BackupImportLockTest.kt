package io.github.katznmeik.cinexperience.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.CinemaDetailRoute
import io.github.katznmeik.cinexperience.feature.cinemas.CinemasRoute
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app-wide backup import lock (spec §8): an import started in another window of
 * the app (a share opens a second one in the email app's task) covers this window's
 * screens, which then take no touch, no Back and nothing from accessibility services,
 * until it ends; the Settings screen that runs the import isn't covered. Fakes only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class BackupImportLockTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val backup = FakeBackupRepository()
    private lateinit var navController: NavHostController

    private fun showApp() {
        val container = FakeAppContainer(FakeCinexRepository(SampleTickets.store().copy(visits = emptyList(), tickets = emptyList())), backup)
        composeRule.setContent {
            navController = rememberNavController()
            CinexTheme { CinexApp(container, navController = navController) }
        }
        composeRule.waitForIdle()
    }

    private fun isOnCinemaDetail(): Boolean = navController.currentBackStackEntry?.destination?.hasRoute(CinemaDetailRoute::class) == true

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun anImportInAnotherWindowCoversThisOneUntilItEnds() {
        showApp()
        composeRule.runOnUiThread { navController.navigate(CinemaDetailRoute(SampleData.DELPHI_LUX)) }
        composeRule.waitForIdle()
        assertTrue(isOnCinemaDetail())
        // Where the back arrow is (px in the root), to tap it under the lock.
        val back = composeRule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot.center

        // The other window's import starts.
        backup.isImporting.value = true
        composeRule.waitForIdle()
        val title = composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TITLE).assertExists().fetchSemanticsNode()
        assertTrue("announced as a heading", SemanticsProperties.Heading in title.config)
        composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TEXT).assertExists()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/backup_import_lock.png")
        composeRule.assertAccessible()
        // Nothing below can be reached: not by accessibility services, not by touch, not by Back.
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        composeRule.onRoot().performTouchInput { click(back) }
        composeRule.waitForIdle()
        assertTrue("the back arrow took no tap", isOnCinemaDetail())
        pressBack()
        assertTrue("Back did nothing", isOnCinemaDetail())

        // The import ends: the window works again.
        backup.isImporting.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TITLE).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        assertEquals(true, navController.currentBackStackEntry?.destination?.hasRoute(CinemasRoute::class))
    }

    @Test
    fun theSettingsScreenThatRunsTheImportIsNotCovered() {
        showApp()
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Import backup").performScrollTo().performClick()
        composeRule.waitForIdle()
        val picker = shadowOf(composeRule.activity).nextStartedActivityForResult
        shadowOf(composeRule.activity).receiveResult(
            picker.intent,
            Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://com.example.documents/backup.zip")),
        )
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Replace").fetchSemanticsNodes().isNotEmpty() }

        val gate = CompletableDeferred<Unit>()
        backup.gate = gate
        composeRule.onNodeWithText("Replace").performClick()
        composeRule.waitUntil(10_000) { backup.isImporting.value }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Importing… 50%").assertExists()
        composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TITLE).assertDoesNotExist()

        gate.complete(Unit)
        composeRule.waitUntil(10_000) { !backup.isImporting.value }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TITLE).assertDoesNotExist()
    }
}
