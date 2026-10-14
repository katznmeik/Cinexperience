package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailScreen
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailUiState
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailViewModel
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorActions
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorScreen
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hall detail and layout editor at large font scales, plus the basic TalkBack
 * checks of [assertAccessible]. (The rated hall detail at 1.5 and 2.0 is in
 * [SeatMapScreenshotTest] as `hall_detail_font_150/200`.)
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
class SeatMapAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface(fontScale) { content() } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    private fun hallState(repository: FakeCinexRepository, hallId: Long, select: (HallDetailViewModel) -> Unit = {}): HallDetailUiState {
        val vm = HallDetailViewModel(hallId, repository, repository)
        select(vm)
        return runBlocking { vm.uiState.first { it !is HallDetailUiState.Loading } }
    }

    @Composable
    private fun hallDetail(state: HallDetailUiState) {
        HallDetailScreen(state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {}, onSeatClick = {}, onSeatChosen = {})
    }

    @Test
    fun hallDetailWithSelectedSeat() {
        val state = hallState(FakeCinexRepository(RatedHallFixture.store()), RatedHallFixture.HALL) {
            it.onSeatClick(RatedHallFixture.favourite)
        }
        check("hall_detail_a11y") { hallDetail(state) }
    }

    @Test
    fun hallDetailWithoutLayout() {
        val state = hallState(FakeCinexRepository(SampleData.storeWithSplitHall()), SampleData.DELPHI_LUX_SAAL_6)
        check("hall_detail_empty") { hallDetail(state) }
    }

    @Test
    fun layoutEditor() {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(3)
        val state = vm.uiState.value
        check("layout_editor") { LayoutEditorScreen(state, referenceImage = null, actions = object : LayoutEditorActions {}) }
    }
}
