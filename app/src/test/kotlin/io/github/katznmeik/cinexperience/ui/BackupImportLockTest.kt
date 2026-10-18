package io.github.katznmeik.cinexperience.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.CinemaDetailRoute
import io.github.katznmeik.cinexperience.feature.cinemas.CinemasRoute
import io.github.katznmeik.cinexperience.feature.visits.EditVisitRoute
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
import org.robolectric.shadows.ShadowUiAutomation

/**
 * The app-wide backup import lock (spec §8): an import started in another window of
 * the app (a share opens a second one in the email app's task) covers this window
 * with a dialog of its own until it ends: no touch, no Back and nothing for
 * accessibility services reaches the screens below, nor a dialog that was open
 * already; the Settings screen that runs the import isn't covered, not even for a
 * frame. Touches and keys are delivered as the system would (to the topmost window
 * under the finger, or with the key focus), not straight to a node. Fakes only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class BackupImportLockTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val backup = FakeBackupRepository()
    private lateinit var navController: NavHostController
    private lateinit var repository: FakeCinexRepository

    private fun showApp(store: FakeStore = SampleTickets.store().copy(visits = emptyList(), tickets = emptyList())) {
        repository = FakeCinexRepository(store)
        val container = FakeAppContainer(repository, backup)
        composeRule.setContent {
            navController = rememberNavController()
            CinexTheme { CinexApp(container, navController = navController) }
        }
        composeRule.waitForIdle()
    }

    private fun isOn(route: kotlin.reflect.KClass<*>): Boolean = navController.currentBackStackEntry?.destination?.hasRoute(route) == true

    private fun navigate(route: Any) {
        composeRule.runOnUiThread { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    /** Where [node]'s centre is on the screen (taken before the lock hides it). */
    private fun screenCentreOf(node: SemanticsNodeInteraction): Pair<Float, Float> {
        val semantics = node.fetchSemanticsNode()
        return semantics.positionOnScreen.x + semantics.size.width / 2f to semantics.positionOnScreen.y + semantics.size.height / 2f
    }

    /** A tap as the system delivers it: to the topmost touchable window under the finger. */
    private fun tapOnScreen(at: Pair<Float, Float>) {
        val time = SystemClock.uptimeMillis()
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 50L)) {
            val event = MotionEvent.obtain(time, time + offset, action, at.first, at.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            ShadowUiAutomation.injectInputEvent(event)
        }
        composeRule.waitForIdle()
    }

    /** Back as the system delivers it: to the window with the key focus. */
    private fun pressBackKey() {
        val time = SystemClock.uptimeMillis()
        ShadowUiAutomation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        ShadowUiAutomation.injectInputEvent(KeyEvent(time, time + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        composeRule.waitForIdle()
    }

    @Test
    fun anImportInAnotherWindowCoversThisOneUntilItEnds() {
        showApp()
        navigate(CinemaDetailRoute(SampleData.DELPHI_LUX))
        val back = screenCentreOf(composeRule.onNodeWithContentDescription("Back"))

        // The other window's import starts.
        backup.isImporting.value = true
        composeRule.waitForIdle()
        val title = composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TITLE).assertExists().fetchSemanticsNode()
        assertTrue("announced as a heading", SemanticsProperties.Heading in title.config)
        composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TEXT).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/backup_import_lock.png")
        composeRule.assertAccessible()
        // Nothing below can be reached: not by accessibility services, not by touch, not by Back.
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        tapOnScreen(back)
        assertTrue("the back arrow took no tap", isOn(CinemaDetailRoute::class))
        pressBackKey()
        assertTrue("Back did nothing", isOn(CinemaDetailRoute::class))

        // The import ends: the window works again, with the same tap and the same key.
        backup.isImporting.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TITLE).assertDoesNotExist()
        tapOnScreen(back)
        assertTrue(isOn(CinemasRoute::class))
        navigate(CinemaDetailRoute(SampleData.DELPHI_LUX))
        pressBackKey()
        assertTrue(isOn(CinemasRoute::class))
    }

    /** A dialog is a window of its own: the lock lies above one that was open already. */
    @Test
    fun aDialogOpenBeforeTheImportCantBeConfirmedUnderTheLock() {
        showApp(SampleTickets.store())
        navigate(EditVisitRoute(SampleTickets.VISIT))
        composeRule.onNodeWithText("Delete visit").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete this visit?").assertExists()
        val delete = screenCentreOf(composeRule.onNodeWithText("Delete"))

        backup.isImporting.value = true
        composeRule.waitForIdle()
        tapOnScreen(delete)
        assertTrue("not deleted under the lock", repository.store.value.visits.any { it.id == SampleTickets.VISIT })
        composeRule.onNodeWithText("Delete this visit?").assertExists()

        backup.isImporting.value = false
        composeRule.waitForIdle()
        tapOnScreen(delete)
        assertTrue("deleted once the import has ended", repository.store.value.visits.none { it.id == SampleTickets.VISIT })
    }

    /** Back on a screen opened under the lock (its own Back handling comes later) is swallowed too. */
    @Test
    fun backOnAScreenOpenedUnderTheLockDoesNothing() {
        showApp(SampleTickets.store())
        backup.isImporting.value = true
        composeRule.waitForIdle()
        navigate(EditVisitRoute(SampleTickets.VISIT))
        pressBackKey()
        assertTrue(isOn(EditVisitRoute::class))

        backup.isImporting.value = false
        composeRule.waitForIdle()
        pressBackKey()
        assertTrue("the unchanged form closes", isOn(CinemasRoute::class))
    }

    @Test
    fun theSettingsScreenThatRunsTheImportIsNeverCoveredNotEvenForAFrame() {
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
        fun lockShown() = composeRule.onAllNodesWithText(BACKUP_IMPORT_LOCK_TITLE).fetchSemanticsNodes().isNotEmpty()
        // Frame by frame from the confirmation on.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Replace").performClick()
        assertTrue("the import runs", backup.isImporting.value)
        repeat(10) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            assertTrue("no lock in frame $frame", !lockShown())
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Importing… 50%").assertExists()
        assertTrue(!lockShown())

        gate.complete(Unit)
        composeRule.waitUntil(10_000) { !backup.isImporting.value }
        composeRule.waitForIdle()
        assertTrue(!lockShown())
        assertEquals(false, backup.isImporting.value)
    }
}
