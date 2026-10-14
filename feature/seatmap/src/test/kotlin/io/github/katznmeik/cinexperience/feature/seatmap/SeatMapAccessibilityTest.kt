package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
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
    private fun HallDetail(state: HallDetailUiState) {
        HallDetailScreen(state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {}, onSeatClick = {}, onSeatChosen = {})
    }

    @Test
    fun hallDetailWithSelectedSeat() {
        val state = hallState(FakeCinexRepository(RatedHallFixture.store()), RatedHallFixture.HALL) {
            it.onSeatClick(RatedHallFixture.favourite)
        }
        check("hall_detail_a11y") { HallDetail(state) }
    }

    @Test
    fun hallDetailWithoutLayout() {
        val state = hallState(FakeCinexRepository(SampleData.storeWithSplitHall()), SampleData.DELPHI_LUX_SAAL_6)
        check("hall_detail_empty") { HallDetail(state) }
    }

    @Test
    fun layoutEditor() {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(3)
        val state = vm.uiState.value
        check("layout_editor") { LayoutEditorScreen(state, referenceImage = null, actions = object : LayoutEditorActions {}) }
    }

    /**
     * A right-to-left row in three blocks: the "Direction" chips wrap, the "Starts at"
     * fields follow the numbering direction and name their block for TalkBack.
     */
    @Test
    fun layoutEditorRightToLeftRow() {
        val repository = FakeCinexRepository(SampleData.storeWithYorckNumbering())
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(3)
        val state = vm.uiState.value
        check("layout_editor_right_to_left") { LayoutEditorScreen(state, referenceImage = null, actions = object : LayoutEditorActions {}) }
        composeRule.onNodeWithContentDescription("Numbered right to left").assertIsSelected()
        composeRule.onNodeWithContentDescription("Right block start number, empty for automatic").assertExists()
    }

    /** The selected "R" wheelchair space: the seat card says "Row 6 · Seat R" and "Wheelchair space". */
    @Test
    fun hallDetailWithAFixedLabel() {
        val state = hallState(FakeCinexRepository(SampleData.storeWithYorckNumbering()), SampleData.DELPHI_LUX_SAAL_1) {
            it.onSeatClick(SampleLayouts.yorckRowSixR)
        }
        check("hall_detail_fixed_label") { HallDetail(state) }
    }

    /**
     * Kant Kino Saal 3: rows without a name, sofa places. The selected seat card reads
     * "SEAT 12", the map has no row labels, the seat is announced as "Seat 12, sofa".
     */
    @Test
    fun hallDetailKantKinoSaal3() {
        val state = hallState(FakeCinexRepository(SampleData.storeWithKantKinoSaal3()), SampleData.KANT_KINO_SAAL_3) {
            it.onSeatClick(SampleLayouts.kantKinoSaal3Seat12)
        }
        check("hall_detail_kant_kino_saal_3") { HallDetail(state) }
        // The card title ("SEAT 12" on screen) is announced with the seat's state.
        composeRule.onNodeWithContentDescription("Seat 12, sofa, no data").assertExists()
    }

    /** An unnamed row selected in the editor: the label field holds "-", the title reads "Unnamed row". */
    @Test
    fun layoutEditorUnnamedRow() {
        val repository = FakeCinexRepository(SampleData.storeWithKantKinoSaal3())
        val vm = LayoutEditorViewModel(SampleData.KANT_KINO_SAAL_3, repository, FakeReferenceImageStore())
        vm.selectRow(1)
        val state = vm.uiState.value
        check("layout_editor_unnamed_row") { LayoutEditorScreen(state, referenceImage = null, actions = object : LayoutEditorActions {}) }
        composeRule.onNodeWithContentDescription("Row label, empty for automatic, a dash for no name").assertExists()
        composeRule.onNodeWithText("no name").assertExists()
    }

    /** The selected screen: the screen panel ("Done", width, distance and position steppers) in place of the row panel. */
    @Test
    fun layoutEditorWithSelectedScreen() {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectScreen()
        vm.changeScreenOffset(-1.5f)
        val state = vm.uiState.value
        check("layout_editor_screen") { LayoutEditorScreen(state, referenceImage = null, actions = object : LayoutEditorActions {}) }
    }
}
