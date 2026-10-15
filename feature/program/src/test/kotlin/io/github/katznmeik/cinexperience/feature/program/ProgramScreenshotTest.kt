package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.FilmKey
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import io.github.katznmeik.cinexperience.feature.program.ProgramScreenFixtures as F

/**
 * Screenshots of the Program tab and the film page with the INVENTED [io.github.katznmeik.cinexperience.core.testing.SampleProgram],
 * phone-sized, dark theme. PNGs land in feature/program/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class ProgramScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: ProgramUiState, actions: ProgramActions = ProgramActions.None) {
        composeRule.setContent { ScreenshotSurface { ProgramScreen(state, actions) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun today() = capture("program_today", F.state())

    @Test
    fun filtersOpen() = capture("program_filters", F.state(filter = F.busyFilter, expanded = true))

    @Test
    fun thisWeek() = capture("program_this_week", F.state(filter = F.busyFilter.copy(cinemas = emptySet(), from = null, to = null, versions = emptySet(), events = emptySet())))

    @Test
    fun chosenDates() = capture(
        "program_dates",
        F.state(filter = ProgramFilter(day = DayChoice.DATES, dates = setOf(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 19)))),
    )

    @Test
    fun consent() {
        var allowed = 0
        capture(
            "program_consent",
            F.state(program = null, mode = ProgramMode.CONSENT),
            object : ProgramActions {
                override fun onAllowDownloads() {
                    allowed++
                }
            },
        )
        composeRule.onNodeWithText("Download program").performClick()
        assertEquals(1, allowed)
    }

    @Test
    fun notNow() = capture("program_not_now", F.state(program = null, mode = ProgramMode.NOT_NOW))

    @Test
    fun off() = capture("program_off", F.state(program = null, mode = ProgramMode.OFF))

    @Test
    fun loading() = capture("program_first_download", F.state(program = null, refreshing = true))

    @Test
    fun errorKeepsTheProgram() = capture("program_error", F.state(error = ProgramError.OFFLINE))

    @Test
    fun errorWithoutProgram() = capture("program_error_no_program", F.state(program = null, error = ProgramError.UNREADABLE))

    @Test
    fun empty() = capture("program_empty", F.state(filter = ProgramFilter(search = "zzz")))

    @Test
    fun actionsReachTheViewModel() {
        val booked = mutableListOf<String>()
        val films = mutableListOf<FilmKey>()
        val state = F.state()
        capture(
            "program_today",
            state,
            object : ProgramActions {
                override fun onBook(showingId: String) {
                    booked += showingId
                }

                override fun onOpenFilm(film: FilmKey) {
                    films += film
                }
            },
        )
        val first = state.days.first().showings.first()
        composeRule.onAllNodesWithContentDescription("Book on yorck.de: ", substring = true)[0].performClick()
        composeRule.onAllNodesWithText("Neon Harbour")[0].performClick()
        assertEquals(listOf(first.showingId), booked)
        assertEquals(listOf(FilmKey(FilmKind.FILM, "neon-harbour")), films)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun savedFiltersDialog() {
        val state = F.state().copy(
            savedFilters = listOf(SavedFilter("After work", F.busyFilter), SavedFilter("Kids weekend", ProgramFilter(day = DayChoice.WEEKEND))),
            dialog = ProgramDialog.SAVED_FILTERS,
        )
        composeRule.setContent { ScreenshotSurface { ProgramScreen(state, ProgramActions.None) } }
        composeRule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/program_saved_filters.png")
    }

    @Test
    fun filmPage() {
        composeRule.setContent { ScreenshotSurface { FilmScreen(F.film(), FilmActions.None) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/film_page.png")
    }

    @Test
    fun specialPage() {
        composeRule.setContent { ScreenshotSurface { FilmScreen(F.film(FilmKey(FilmKind.SPECIAL, "midnight-surprise-screening")), FilmActions.None) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/film_page_special.png")
    }

    /** A series that only links showings owned by films. */
    @Test
    fun seriesPage() {
        composeRule.setContent { ScreenshotSurface { FilmScreen(F.film(SampleProgram.AUTUMN_CLASSICS), FilmActions.None) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("The Quiet Projectionist").assertExists()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/film_page_series.png")
    }

    /** Two cinemas may have the same name (the owner renames cinemas): keys use the cinema key. */
    @Test
    fun sameCinemaNamesDontClash() {
        val film = F.film()
        val content = film.content as FilmContent.Loaded
        val twins = content.cinemas.take(2).map { it.copy(cinemaName = "Twin") }
        composeRule.setContent { ScreenshotSurface { FilmScreen(film.copy(content = content.copy(cinemas = twins)), FilmActions.None) } }
        composeRule.waitForIdle()
        // Before the fix this crashed: "Key … was already used".
        assertEquals(2, composeRule.onAllNodesWithContentDescription("Twin", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun offWithAProgram() = capture("program_off_cached", F.state(mode = ProgramMode.OFF))

    @Test
    fun filmNotFound() {
        composeRule.setContent { ScreenshotSurface { FilmScreen(FilmUiState(content = FilmContent.NotFound), FilmActions.None) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/film_page_not_found.png")
    }
}

/** The Program tab and the film page at font scales 1, 1.5 and 2, with the TalkBack checks. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class ProgramAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface(fontScale) { content() } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun results() = check("program_filters") { ProgramScreen(F.state(filter = F.busyFilter, expanded = true), ProgramActions.None) }

    @Test
    fun resultsToday() = check("program_today") { ProgramScreen(F.state(), ProgramActions.None) }

    @Test
    fun consent() = check("program_consent") { ProgramScreen(F.state(program = null, mode = ProgramMode.CONSENT), ProgramActions.None) }

    @Test
    fun off() = check("program_off") { ProgramScreen(F.state(program = null, mode = ProgramMode.OFF), ProgramActions.None) }

    @Test
    fun error() = check("program_error") { ProgramScreen(F.state(error = ProgramError.OFFLINE), ProgramActions.None) }

    @Test
    fun empty() = check("program_empty") { ProgramScreen(F.state(filter = ProgramFilter(search = "zzz")), ProgramActions.None) }

    @Test
    fun dates() = check("program_dates") {
        ProgramScreen(F.state(filter = ProgramFilter(day = DayChoice.DATES, dates = setOf(LocalDate.of(2026, 10, 17)))), ProgramActions.None)
    }

    @Test
    fun filmPage() = check("film_page") { FilmScreen(F.film(), FilmActions.None) }

    @Test
    fun seriesPage() = check("film_page_series") { FilmScreen(F.film(SampleProgram.AUTUMN_CLASSICS), FilmActions.None) }

    @Test
    fun specialPage() = check("film_page_special") { FilmScreen(F.film(SampleProgram.MIDNIGHT_SURPRISE), FilmActions.None) }

    @Test
    fun filmNotFound() = check("film_page_not_found") { FilmScreen(FilmUiState(content = FilmContent.NotFound), FilmActions.None) }

    @Test
    fun notNow() = check("program_not_now") { ProgramScreen(F.state(program = null, mode = ProgramMode.NOT_NOW), ProgramActions.None) }

    @Test
    fun offWithAProgram() = check("program_off_cached") { ProgramScreen(F.state(mode = ProgramMode.OFF), ProgramActions.None) }

    /**
     * A dialog over the tab. The clock is held, so the dialog's animations and a
     * text cursor can't keep Compose busy; the screen capture includes the dialog.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun checkDialog(name: String, dialog: ProgramDialog, state: ProgramUiState = F.state()) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface(fontScale) { ProgramScreen(state.copy(dialog = dialog), ProgramActions.None) } }
        composeRule.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun savedFiltersDialog() = checkDialog(
        "program_saved_filters",
        ProgramDialog.SAVED_FILTERS,
        F.state().copy(savedFilters = listOf(SavedFilter("After work", F.busyFilter), SavedFilter("Kids weekend", ProgramFilter(day = DayChoice.WEEKEND)))),
    )

    /**
     * "Save filter": its name field (the AlertDialog with a text field never lets
     * Robolectric go idle, so the field is checked on its own, empty and with an error).
     */
    @Test
    fun saveFilterName() {
        composeRule.setContent {
            ScreenshotSurface(fontScale) {
                androidx.compose.foundation.layout.Column {
                    SaveFilterName("", null) {}
                    SaveFilterName("After work", ProgramViewModel.NAME_TOO_LONG) {}
                }
            }
        }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/program_save_filter_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible(requireHeading = false)
    }

    @Test
    fun replaceFilterDialog() = checkDialog("program_replace_filter", ProgramDialog.CONFIRM_REPLACE, F.state().copy(pendingReplace = "After work"))
}
