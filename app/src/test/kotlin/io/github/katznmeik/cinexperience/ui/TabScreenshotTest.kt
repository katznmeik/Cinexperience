package io.github.katznmeik.cinexperience.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRankingsRepository
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.di.AppContainer
import io.github.katznmeik.cinexperience.feature.cinemas.closeDeletedCinema
import io.github.katznmeik.cinexperience.feature.tickets.importing.ContentTicketFileSource
import io.github.katznmeik.cinexperience.feature.tickets.importing.DefaultTicketReader
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketFileSource
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketReader
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.ZoneOffset

/**
 * App container backed by the in-memory fake repositories. Its [clock] stands at
 * the sample program's download time (15 Oct 2026, 10:00 in Berlin), so the
 * screens that read the time don't depend on the day the tests run. The ticket
 * import reads files with the app's own source and reader unless a test passes fakes.
 */
internal class FakeAppContainer(
    repository: FakeCinexRepository,
    override val backupRepository: FakeBackupRepository = FakeBackupRepository(),
    override val programRepository: FakeProgramRepository = FakeProgramRepository(),
    override val clock: Clock = Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC),
    override val ticketFileSource: TicketFileSource = ContentTicketFileSource(RuntimeEnvironment.getApplication()),
    override val ticketReader: TicketReader = DefaultTicketReader,
) : AppContainer {
    override val applicationContext: Context = RuntimeEnvironment.getApplication()
    override val appVersion: String = "0.1.0"
    override val buildId: String = "abc1234"
    override val cinemaRepository = repository
    override val hallRepository = repository
    override val visitRepository = repository
    override val seatOverrideRepository = repository
    override val ticketRepository = repository
    override val rankingsRepository = CinemaRankingsRepository(repository, repository)
    override val referenceImageStore = FakeReferenceImageStore()
    override val visitPhotoStore = FakeVisitPhotoStore()
    override val programFilterStore = InMemoryProgramFilterStore()
}

/**
 * Screenshots of the three tab screens through the real navigation graph,
 * phone-sized (412×915 dp, xxhdpi) in the dark theme, with the preloaded cinemas
 * as after a fresh install (no visits). Record with `./gradlew recordRoborazziDebug`;
 * PNGs land in app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TabScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showApp(repository: FakeCinexRepository = FakeCinexRepository(SampleData.freshInstallStore())) {
        val container = FakeAppContainer(repository)
        composeRule.setContent {
            CinexTheme { CinexApp(container) }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun cinemasTab() {
        showApp()
        composeRule.onNodeWithText("13 venues · 0 visits").assertExists()
        capture("tab_cinemas")
    }

    @Test
    fun rankingsTab() {
        showApp()
        composeRule.onNodeWithTag(TopLevelDestination.Rankings.testTag).performClick()
        capture("tab_rankings")
    }

    @Test
    fun settingsTab() {
        showApp()
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()
        capture("tab_settings")
    }

    /** While a backup import replaces the data, the tabs can't be switched (nothing else may write). */
    @Test
    fun tabsAreLockedWhileImporting() {
        val backup = FakeBackupRepository()
        val container = FakeAppContainer(FakeCinexRepository(SampleData.freshInstallStore()), backup)
        composeRule.setContent { CinexTheme { CinexApp(container) } }
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()

        backup.isImporting.value = true
        composeRule.onNodeWithTag(TopLevelDestination.Cinemas.testTag).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(TopLevelDestination.Rankings.testTag).performClick()
        composeRule.onNodeWithText("13 cinemas · 36 halls · 0 layouts drawn").assertExists()

        backup.isImporting.value = false
        composeRule.onNodeWithTag(TopLevelDestination.Cinemas.testTag).assertIsEnabled().performClick()
        composeRule.onNodeWithText("13 venues · 0 visits").assertExists()
    }

    @Test
    fun cinemaToHallNavigation() {
        showApp()
        composeRule.onNodeWithText("KANT KINO").performClick()
        composeRule.onNodeWithText("Saal 3").performClick()
        composeRule.onNodeWithText("NO LAYOUT YET").assertExists()
        composeRule.onNodeWithText("Kant Kino · no visits").assertExists()
        capture("hall_detail_no_layout")
    }

    /** "Draw layout" opens the editor with a default grid; Discard without changes goes straight back. */
    @Test
    fun hallToLayoutEditorAndBack() {
        showApp()
        composeRule.onNodeWithText("KANT KINO").performClick()
        composeRule.onNodeWithText("Saal 3").performClick()
        composeRule.onNodeWithText("Draw layout").performClick()
        composeRule.onNodeWithText("SAAL 3 · LAYOUT").assertExists()
        composeRule.onNodeWithText("16 seats").assertExists() // default screen width = 16 columns
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithText("NO LAYOUT YET").assertExists()
    }

    /** "Add visit" on a hall opens the form with that hall; closing without changes goes straight back. */
    @Test
    fun hallToAddVisitAndBack() {
        showApp()
        composeRule.onNodeWithText("KANT KINO").performClick()
        composeRule.onNodeWithText("Saal 3").performClick()
        composeRule.onNodeWithText("Add visit").performScrollTo().performClick()
        composeRule.onNodeWithText("ADD VISIT").assertExists()
        composeRule.onNodeWithContentDescription("Cinema, Kant Kino").assertExists()
        composeRule.onNodeWithContentDescription("Hall, Saal 3").assertExists()
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithText("NO LAYOUT YET").assertExists()
    }

    /** The Cinemas FAB opens the form without a hall; Settings → All visits shows the empty list. */
    @Test
    fun visitEntryPoints() {
        showApp()
        composeRule.onNodeWithText("Add visit", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("ADD VISIT").assertExists()
        composeRule.onNodeWithContentDescription("Hall, not chosen, choose cinema first").assertExists()
        composeRule.onNodeWithContentDescription("Close").performClick()

        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.onNodeWithText("All visits").performClick()
        composeRule.onNodeWithText("ALL VISITS").assertExists()
    }

    /**
     * The detail screen hands its "Deleted …" message to the screen below. (The
     * dialogs themselves are covered by the ViewModel tests: Robolectric never
     * reports idle while an AlertDialog is shown.)
     */
    @Test
    fun deletedCinemaMessageIsShownOnTheList() {
        val repository = FakeCinexRepository(SampleData.freshInstallStore())
        val id = runBlocking { repository.createCinema("Atelier") }
        val container = FakeAppContainer(repository)
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            CinexTheme { CinexApp(container, navController = navController) }
        }

        composeRule.onNodeWithText("ATELIER").performClick()
        composeRule.onNodeWithContentDescription("Edit cinema").assertExists()
        composeRule.runOnIdle {
            runBlocking { repository.deleteCinema(id) }
            navController.closeDeletedCinema("Deleted Atelier")
        }

        // A real-time wait: generous, so a slow machine doesn't fail it.
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.onAllNodesWithText("Deleted Atelier").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("13 venues · 0 visits").assertExists()
    }
}
