package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsScreen
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsUiState
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Screenshots of the visit form, its seat picker and the all-visits list with
 * invented data ([VisitFixtures]), phone-sized, dark theme. PNGs land in
 * feature/visits/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class VisitsScreenshotTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val noActions = object : VisitEditActions {}

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) { content() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun form(mode: VisitEditMode, store: FakeStore = VisitFixtures.store(), setUp: VisitEditViewModel.() -> Unit = {}): VisitEditViewModel {
        val photos = FakeVisitPhotoStore(setOf("photos/visit-fixture.jpg"))
        val repository = FakeCinexRepository(store)
        return VisitEditViewModel(mode, repository, repository, photos, SavedStateHandle(), today = { LocalDate.of(2026, 10, 14) }).apply(setUp)
    }

    @Test
    fun addVisitFromHall() {
        val state = form(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1)).uiState.value
        capture("add_visit_from_hall") { VisitEditScreen(state, photo = null, actions = noActions) }
    }

    @Test
    fun addVisitFromCinemasTab() {
        val state = form(VisitEditMode.Add(null)).uiState.value
        capture("add_visit_no_hall") { VisitEditScreen(state, photo = null, actions = noActions) }
    }

    @Test
    fun editVisitFilled() {
        val state = form(VisitEditMode.Edit(VisitFixtures.EDITED_VISIT)).uiState.value
        check(state.seatLabel != null && state.form.photoPath != null)
        val photo = VisitFixtures.photo().asImageBitmap()
        capture("edit_visit_filled") { VisitEditScreen(state, photo = photo, actions = noActions) }
    }

    /** The whole filled form on a tall screen, to review every section at once. */
    @Test
    @Config(qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
    fun editVisitFilledFullLength() {
        val state = form(VisitEditMode.Edit(VisitFixtures.EDITED_VISIT)).uiState.value
        val photo = VisitFixtures.photo().asImageBitmap()
        capture("edit_visit_filled_full") { VisitEditScreen(state, photo = photo, actions = noActions) }
    }

    @Test
    fun seatPicker() {
        val vm = form(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1)) {
            openSeatPicker()
            tapPickerSeat(SampleLayouts.splitHall.rows[6].seatIds[13]) // row 7, seat 10
        }
        val state = vm.uiState.value
        capture("seat_picker") { VisitEditScreen(state, photo = null, actions = noActions) }
    }

    /**
     * The seat map of the picker shows every seat number (spec §6.4); here a companion
     * seat in the back row is selected: its symbol sits above its number.
     */
    @Test
    fun seatPickerWithNumbers() {
        val vm = form(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1)) {
            openSeatPicker()
            tapPickerSeat(SampleLayouts.splitHall.rows[9].seatIds[1]) // row 10, companion seat 1
        }
        val state = vm.uiState.value
        capture("seat_picker_numbers") { VisitEditScreen(state, photo = null, actions = noActions) }
    }

    @Test
    fun allVisits() {
        val state = allVisitsState(VisitFixtures.store())
        capture("all_visits") { AllVisitsScreen(state, onBack = {}, onOpenVisit = {}) }
    }

    @Test
    fun allVisitsEmpty() {
        val state = allVisitsState(SampleData.freshInstallStore())
        capture("all_visits_empty") { AllVisitsScreen(state, onBack = {}, onOpenVisit = {}) }
    }

    private fun allVisitsState(store: FakeStore): AllVisitsUiState {
        val vm = AllVisitsViewModel(FakeCinexRepository(store))
        return runBlocking { vm.uiState.first { !it.isLoading } }
    }
}
