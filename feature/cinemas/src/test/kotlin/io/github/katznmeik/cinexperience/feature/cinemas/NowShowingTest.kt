package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailContent
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailScreen
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailUiState
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.detail.NowShowing
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** INVENTED showings at delphi LUX over a week, around Thursday 15 Oct 2026. */
internal object NowShowingFixtures {
    val program = buildProgram {
        film("The Quiet Projectionist", runtimeMinutes = 118) {
            showing("2026-10-15T09:30", "delphi LUX", ShowingVersion.OMU) // already started at 10:00
            showing("2026-10-15T16:30", "delphi LUX", ShowingVersion.OMU)
            showing("2026-10-16T17:15", "delphi LUX", ShowingVersion.OMU)
            showing("2026-10-21T20:00", "delphi LUX", ShowingVersion.OV) // Wednesday: the 7th day
            showing("2026-10-22T20:00", "delphi LUX", ShowingVersion.OV) // the 8th day: not shown
            showing("2026-10-15T20:45", "Kant Kino", ShowingVersion.OV)
        }
        film("Neon Harbour", runtimeMinutes = 97) {
            showing("2026-10-15T21:30", "delphi LUX", ShowingVersion.OMEU, eventLabels = listOf("Premiere"))
        }
        film("Midnight Surprise Screening", kind = FilmKind.SPECIAL) {
            showing("2026-10-17T23:00", "delphi LUX", ShowingVersion.OV, eventLabels = listOf("Sneak"))
        }
    }

    val now = SampleProgram.FETCHED_AT
    val today: LocalDate = LocalDate.of(2026, 10, 15)

    fun detail(cinemaId: Long = SampleData.DELPHI_LUX): CinemaDetail =
        runBlocking { FakeCinexRepository(SampleData.store()).observeCinemaDetail(cinemaId).first()!! }
}

/** "Now showing" (spec §6.2): only the downloaded program, the next 7 days, by day. */
class NowShowingStateTest {

    private val loaded = ProgramState(permission = DownloadPermission.ALLOWED, program = NowShowingFixtures.program, isLoaded = true)

    private fun of(state: ProgramState, seedKey: String? = "delphi-lux") =
        NowShowing.of(state, seedKey, NowShowingFixtures.now, NowShowingFixtures.today)

    @Test
    fun theNextSevenDaysByDay() {
        val showings = of(loaded) as NowShowing.Showings
        assertEquals(
            listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 21)),
            showings.days.map { it.date },
        )
        assertEquals(listOf("16:30", "21:30"), showings.days.first().showings.map { it.start.toLocalTime().toString() })
        assertEquals(FilmKind.SPECIAL, showings.days[2].showings.single().filmKind)
    }

    @Test
    fun hintsWithoutAProgram() {
        assertEquals(NowShowing.Hint(NowShowing.HintReason.NOT_ASKED), of(ProgramState(isLoaded = true)))
        assertEquals(NowShowing.Hint(NowShowing.HintReason.OFF), of(ProgramState(permission = DownloadPermission.OFF, isLoaded = true)))
        assertEquals(NowShowing.Hint(NowShowing.HintReason.NOT_DOWNLOADED), of(ProgramState(permission = DownloadPermission.ALLOWED, isLoaded = true)))
    }

    @Test
    fun hiddenUntilLoadedAndForCinemasYorckDoesNotHave() {
        assertEquals(NowShowing.Hidden, of(ProgramState()))
        assertEquals(NowShowing.Hidden, of(loaded, seedKey = null))
        assertEquals(NowShowing.Hidden, of(loaded, seedKey = "my-own-cinema"))
    }
}

class NowShowingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun readsTheProgramButNeverDownloads() = runTest {
        val program = FakeProgramRepository.withProgram(NowShowingFixtures.program)
        val repository = FakeCinexRepository(SampleData.store())
        val clock = Clock.fixed(NowShowingFixtures.now, ZoneOffset.UTC)
        val vm = CinemaDetailViewModel(SampleData.DELPHI_LUX, repository, repository, program, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val nowShowing = vm.uiState.value.nowShowing as NowShowing.Showings
        assertEquals(4, nowShowing.days.size)
        assertEquals("delphi-lux", vm.cinemaSeedKey)
        assertTrue(program.refreshCalls.isEmpty())
        assertEquals(0, program.downloads)
    }
}

private val noDialogActions = object : EditDialogActions {
    override fun onNameChange(name: String) = Unit
    override fun onAddressChange(address: String) = Unit
    override fun onNotesChange(notes: String) = Unit
    override fun onConfirm() = Unit
    override fun onDismiss() = Unit
    override fun onDeleteCinema() = Unit
}

@androidx.compose.runtime.Composable
private fun DetailWith(nowShowing: NowShowing, detail: CinemaDetail = NowShowingFixtures.detail(), onOpenFilm: (FilmKind, String) -> Unit = { _, _ -> }, onShowAll: () -> Unit = {}) {
    CinemaDetailScreen(
        state = CinemaDetailUiState(CinemaDetailContent.Loaded(detail), nowShowing = nowShowing),
        onBack = {},
        onEdit = {},
        onAddHall = {},
        onOpenHall = {},
        onRenameHall = {},
        onDeleteHall = {},
        dialogActions = noDialogActions,
        onMessageShown = {},
        onOpenFilm = onOpenFilm,
        onShowAllInProgram = onShowAll,
    )
}

private val showings: NowShowing = NowShowing.of(
    ProgramState(permission = DownloadPermission.ALLOWED, program = NowShowingFixtures.program, isLoaded = true),
    "delphi-lux",
    NowShowingFixtures.now,
    NowShowingFixtures.today,
)

/** Screenshots of "Now showing" on a tall phone (the section is below the halls). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
class NowShowingScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showings() {
        val opened = mutableListOf<Pair<FilmKind, String>>()
        var showAll = 0
        composeRule.setContent {
            ScreenshotSurface { DetailWith(showings, onOpenFilm = { kind, slug -> opened += kind to slug }, onShowAll = { showAll++ }) }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/cinema_detail_now_showing.png")
        composeRule.onNodeWithText("Midnight Surprise Screening").performScrollTo().performClick()
        composeRule.onNodeWithText("Show all in Program").performScrollTo().performClick()
        assertEquals(listOf(FilmKind.SPECIAL to "midnight-surprise-screening"), opened)
        assertEquals(1, showAll)
    }

    @Test
    fun hintNotAsked() {
        composeRule.setContent { ScreenshotSurface { DetailWith(NowShowing.Hint(NowShowing.HintReason.NOT_ASKED), NowShowingFixtures.detail(SampleData.BABYLON)) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/cinema_detail_now_showing_hint.png")
    }

    @Test
    fun hintOffAndNone() {
        composeRule.setContent { ScreenshotSurface { DetailWith(NowShowing.Hint(NowShowing.HintReason.OFF), NowShowingFixtures.detail(SampleData.BABYLON)) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Downloading the program is switched off. Turn it on in the Program tab or in Settings to see showtimes here.").assertExists()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/cinema_detail_now_showing_off.png")
    }
}

/** "Now showing" at font scales 1, 1.5 and 2 with the TalkBack checks. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class NowShowingAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, nowShowing: NowShowing, detail: CinemaDetail) {
        composeRule.setContent { ScreenshotSurface(fontScale) { DetailWith(nowShowing, detail) } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun showings() = check("cinema_detail_now_showing", showings, NowShowingFixtures.detail(SampleData.BABYLON))

    @Test
    fun hint() = check("cinema_detail_now_showing_hint", NowShowing.Hint(NowShowing.HintReason.NOT_DOWNLOADED), NowShowingFixtures.detail(SampleData.BABYLON))
}
