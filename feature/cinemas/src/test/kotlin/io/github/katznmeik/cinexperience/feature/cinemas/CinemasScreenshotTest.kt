package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailContent
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailScreen
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailUiState
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogActions
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaSort
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasUiState
import io.github.katznmeik.cinexperience.feature.cinemas.list.sortedFor
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the cinema screens with the illustrative [SampleData] (real seed
 * cinema names, invented visits and scores), phone-sized, dark theme. PNGs land in
 * feature/cinemas/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class CinemasScreenshotTest {

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

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) { content() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun cinemasList() {
        val cinemas = runBlocking { repository.observeCinemas().first() }
        val state = CinemasUiState(isLoading = false, sort = CinemaSort.SCORE, cinemas = cinemas.sortedFor(CinemaSort.SCORE))
        capture("cinemas_list") {
            CinemasScreen(state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {})
        }
    }

    @Test
    fun cinemaDetail() {
        val detail = runBlocking { repository.observeCinemaDetail(SampleData.DELPHI_LUX).first()!! }
        capture("cinema_detail") {
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
        capture("manage_cinemas") {
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
}
