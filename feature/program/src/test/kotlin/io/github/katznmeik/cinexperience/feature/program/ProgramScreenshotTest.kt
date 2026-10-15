package io.github.katznmeik.cinexperience.feature.program

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
        val films = mutableListOf<FilmId>()
        val state = F.state()
        capture(
            "program_today",
            state,
            object : ProgramActions {
                override fun onBook(showingId: String) {
                    booked += showingId
                }

                override fun onOpenFilm(film: FilmId) {
                    films += film
                }
            },
        )
        val first = state.days.first().showings.first()
        composeRule.onAllNodesWithContentDescription("Book on yorck.de: ", substring = true)[0].performClick()
        composeRule.onAllNodesWithText("Neon Harbour")[0].performClick()
        assertEquals(listOf(first.showingId), booked)
        assertEquals(listOf(FilmId(FilmKind.FILM, "neon-harbour")), films)
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
        composeRule.setContent { ScreenshotSurface { FilmScreen(F.film(FilmId(FilmKind.SPECIAL, "midnight-surprise-screening")), FilmActions.None) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/film_page_special.png")
    }

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
}
