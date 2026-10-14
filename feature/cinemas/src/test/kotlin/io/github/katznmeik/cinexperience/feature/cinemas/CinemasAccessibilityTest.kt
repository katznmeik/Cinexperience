package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailContent
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailScreen
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailUiState
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogActions
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaFilter
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaSort
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasUiState
import io.github.katznmeik.cinexperience.feature.cinemas.list.shownFor
import io.github.katznmeik.cinexperience.feature.cinemas.list.sortedFor
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasUiState
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cinema screens at large font scales (tall captures, so nothing is hidden
 * below the fold), plus the basic TalkBack checks of [assertAccessible]. PNGs land
 * in feature/cinemas/build/outputs/roborazzi as `<screen>_font_150.png` / `_font_200.png`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class CinemasAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = FakeCinexRepository(SampleData.store())

    private val noDialogActions = object : EditDialogActions {
        override fun onNameChange(name: String) = Unit
        override fun onAddressChange(address: String) = Unit
        override fun onNotesChange(notes: String) = Unit
        override fun onConfirm() = Unit
        override fun onDismiss() = Unit
        override fun onDeleteCinema() = Unit
    }

    private fun check(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface(fontScale) { content() } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun cinemasList() {
        val cinemas = runBlocking { repository.observeCinemas().first() }
        val state = CinemasUiState(isLoading = false, sort = CinemaSort.SCORE, cinemas = cinemas.sortedFor(CinemaSort.SCORE))
        check("cinemas_list") { CinemasScreen(state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {}) }
    }

    @Test
    fun cinemaDetail() {
        val detail = runBlocking { repository.observeCinemaDetail(SampleData.DELPHI_LUX).first()!! }
        check("cinema_detail") {
            CinemaDetailScreen(
                state = CinemaDetailUiState(CinemaDetailContent.Loaded(detail)),
                onBack = {},
                onEdit = {},
                onAddHall = {},
                onOpenHall = {},
                onRenameHall = {},
                onDeleteHall = {},
                dialogActions = noDialogActions,
                onMessageShown = {},
            )
        }
    }

    @Test
    fun manageCinemas() {
        val cinemas = runBlocking { repository.observeCinemaDetails().first() }
        check("manage_cinemas") {
            ManageCinemasScreen(
                state = ManageCinemasUiState(isLoading = false, cinemas = cinemas),
                onBack = {},
                onOpenCinema = {},
                onAddCinema = {},
                onEditCinema = {},
                onDeleteCinema = {},
                onAddHall = {},
                onRenameHall = {},
                onDeleteHall = {},
                dialogActions = noDialogActions,
                onMessageShown = {},
            )
        }
    }

    /** Two favourites: a rated one and an unrated one. */
    private fun favouriteCinemas(): List<CinemaSummary> = runBlocking {
        repository.setFavourite(SampleData.DELPHI_LUX, true)
        repository.setFavourite(SampleData.BABYLON, true)
        repository.observeCinemas().first()
    }

    private fun listState(all: List<CinemaSummary>, filter: CinemaFilter) = CinemasUiState(
        isLoading = false,
        sort = CinemaSort.SCORE,
        cinemas = all.shownFor(filter, CinemaSort.SCORE),
        filter = filter,
        allCinemas = all,
    )

    @Test
    fun cinemasListWithFavourites() {
        val state = listState(favouriteCinemas(), CinemaFilter.ALL)
        check("cinemas_list_favourites") { CinemasScreen(state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {}) }
    }

    @Test
    fun cinemasFavouritesChip() {
        val state = listState(favouriteCinemas(), CinemaFilter.FAVOURITES)
        check("cinemas_favourites_chip") { CinemasScreen(state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {}) }
    }

    @Test
    fun cinemasFavouritesEmpty() {
        val all = runBlocking { repository.observeCinemas().first() }
        val state = listState(all, CinemaFilter.FAVOURITES)
        check("cinemas_favourites_empty") { CinemasScreen(state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {}) }
    }

    @Test
    fun cinemaDetailFavourite() {
        runBlocking { repository.setFavourite(SampleData.DELPHI_LUX, true) }
        val detail = runBlocking { repository.observeCinemaDetail(SampleData.DELPHI_LUX).first()!! }
        check("cinema_detail_favourite") {
            CinemaDetailScreen(
                state = CinemaDetailUiState(CinemaDetailContent.Loaded(detail)),
                onBack = {},
                onEdit = {},
                onAddHall = {},
                onOpenHall = {},
                onRenameHall = {},
                onDeleteHall = {},
                dialogActions = noDialogActions,
                onMessageShown = {},
            )
        }
    }
}
