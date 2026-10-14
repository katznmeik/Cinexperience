package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsScreen
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The visit form, its seat picker and the all-visits list at large font scales
 * (tall captures), plus the basic TalkBack checks of [assertAccessible].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h3000dp-port-night-xxhdpi")
class VisitsAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val noActions = object : VisitEditActions {}

    private fun check(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface(fontScale) { content() } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    private fun form(mode: VisitEditMode, setUp: VisitEditViewModel.() -> Unit = {}): VisitEditViewModel {
        val photos = FakeVisitPhotoStore(setOf("photos/visit-fixture.jpg"))
        val repository = FakeCinexRepository(VisitFixtures.store())
        return VisitEditViewModel(mode, repository, repository, photos, SavedStateHandle(), today = { LocalDate.of(2026, 10, 14) }).apply(setUp)
    }

    @Test
    fun editVisit() {
        val state = form(VisitEditMode.Edit(VisitFixtures.EDITED_VISIT)).uiState.value
        val photo = VisitFixtures.photo().asImageBitmap()
        check("edit_visit") { VisitEditScreen(state, photo = photo, actions = noActions) }
    }

    @Test
    fun addVisitWithoutHall() {
        val state = form(VisitEditMode.Add(null)).uiState.value
        check("add_visit_no_hall") { VisitEditScreen(state, photo = null, actions = noActions) }
    }

    @Test
    fun seatPicker() {
        val state = form(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1)) {
            openSeatPicker()
            tapPickerSeat(SampleLayouts.splitHall.rows[6].seatIds[13])
        }.uiState.value
        check("seat_picker") { VisitEditScreen(state, photo = null, actions = noActions) }
    }

    @Test
    fun allVisits() {
        val state = runBlocking { AllVisitsViewModel(FakeCinexRepository(VisitFixtures.store())).uiState.first { !it.isLoading } }
        check("all_visits") { AllVisitsScreen(state, onBack = {}, onOpenVisit = {}) }
    }

    @Test
    fun allVisitsEmpty() {
        val state = runBlocking { AllVisitsViewModel(FakeCinexRepository(SampleData.freshInstallStore())).uiState.first { !it.isLoading } }
        check("all_visits_empty") { AllVisitsScreen(state, onBack = {}, onOpenVisit = {}) }
    }
}
