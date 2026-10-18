package io.github.katznmeik.cinexperience.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.navigateToAddVisit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The Program tab in the app: fourth tab, "Show all in Program", "Log visit" and the import lock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class ProgramNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun showApp(
        program: FakeProgramRepository = FakeProgramRepository(),
        backup: FakeBackupRepository = FakeBackupRepository(),
    ) {
        val container = FakeAppContainer(FakeCinexRepository(SampleData.freshInstallStore()), backup, program)
        composeRule.setContent {
            navController = rememberNavController()
            CinexTheme { CinexApp(container, navController = navController) }
        }
        composeRule.waitForIdle()
    }

    /**
     * The Program tab builds its state off the main thread, which Compose's idling
     * doesn't wait for: wait for the text itself (real time, generous).
     */
    private fun waitForText(text: String) = composeRule.waitUntil(timeoutMillis = 10_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun fourTabsInOrder() {
        showApp()
        assertEquals(listOf("Cinemas", "Program", "Rankings", "Settings"), TopLevelDestination.entries.map { it.label })
    }

    @Test
    fun programTabAsksFirstAndDownloadsNothing() {
        val program = FakeProgramRepository()
        showApp(program)
        composeRule.onNodeWithTag(TopLevelDestination.Program.testTag).performClick()
        waitForText("Download program")
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/tab_program.png")
        assertEquals(0, program.downloads)
        assertEquals(emptyList<String>(), program.refreshCalls)
    }

    @Test
    fun showAllInProgramOpensTheTabFilteredToTheCinema() {
        val program = FakeProgramRepository.withProgram()
        showApp(program)
        composeRule.onNodeWithText("KANT KINO").performClick()
        composeRule.onNodeWithText("Show all in Program").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TopLevelDestination.Program.testTag).assertIsSelected()
        waitForText("\u2068Kant Kino\u2069 · Any time · All versions")
        // The cinema page itself never downloads; the Program tab only if stale.
        assertEquals(listOf("refreshIfStale"), program.refreshCalls)
    }

    @Test
    fun theRememberedFilterComesBackOnTheNextOpen() {
        val program = FakeProgramRepository.withProgram()
        showApp(program)
        composeRule.onNodeWithText("KANT KINO").performClick()
        composeRule.onNodeWithText("Show all in Program").performScrollTo().performClick()
        waitForText("\u2068Kant Kino\u2069 · Any time · All versions")

        // (The Cinemas tab would come back with the cinema's page, which has no tab bar.)
        composeRule.onNodeWithTag(TopLevelDestination.Rankings.testTag).performClick()
        composeRule.onNodeWithTag(TopLevelDestination.Program.testTag).performClick()
        waitForText("All cinemas · Any time · All versions")
        assertEquals(listOf("refreshIfStale", "refreshIfStale"), program.refreshCalls)
    }

    /** A film title with characters that need escaping in a route survives the trip to Add visit. */
    @Test
    fun aPrefilledTitleSurvivesTheRoute() {
        val title = "Rock & Roll 100% / Teil 2? #1 Über 🎬"
        showApp()
        composeRule.runOnIdle {
            navController.navigateToAddVisit(VisitPrefillArgs(7L, LocalDate.of(2026, 10, 17), title, null))
        }
        composeRule.onNodeWithText("ADD VISIT").assertExists()
        composeRule.onNodeWithText(title).assertExists()
    }

    @Test
    fun logVisitOpensAddVisitPrefilled() {
        showApp()
        composeRule.runOnIdle {
            navController.navigateToAddVisit(VisitPrefillArgs(7L, LocalDate.of(2026, 10, 17), "Paper Kites", ScreeningFormat.OMU))
        }
        composeRule.onNodeWithText("ADD VISIT").assertExists()
        composeRule.onNodeWithContentDescription("Cinema, International").assertExists()
        composeRule.onNodeWithContentDescription("Hall, Saal 1").assertExists()
    }

    @Test
    fun programTabIsLockedWhileImporting() {
        val backup = FakeBackupRepository()
        showApp(backup = backup)
        backup.isImporting.value = true
        composeRule.onNodeWithTag(TopLevelDestination.Program.testTag).assertIsNotEnabled().performClick()
        // Still the Cinemas tab, under the lock of an import in another window (BackupImportLockTest).
        composeRule.onNodeWithText(BACKUP_IMPORT_LOCK_TITLE).assertExists()
        composeRule.onNodeWithText("13 venues · 0 visits", useUnmergedTree = true).assertExists()
    }
}
