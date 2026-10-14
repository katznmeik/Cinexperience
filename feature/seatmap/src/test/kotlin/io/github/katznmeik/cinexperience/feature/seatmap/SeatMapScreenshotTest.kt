package io.github.katznmeik.cinexperience.feature.seatmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapViewState
import io.github.katznmeik.cinexperience.core.seatmapui.ViewTransform
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexColors
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.core.ui.theme.DarkCinexColors
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailScreen
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailUiState
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailViewModel
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutDraft
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorActions
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorScreen
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorUiState
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
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
 * Screenshots of the hall detail and the layout editor with invented data
 * ([SampleLayouts.splitHall]: three blocks, a staggered row, restart numbering,
 * wheelchair, companion and sofa seats), phone-sized, dark theme. PNGs land in
 * feature/seatmap/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class SeatMapScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var repository = FakeCinexRepository(SampleData.storeWithSplitHall())

    private fun capture(
        name: String,
        fontScale: Float? = null,
        colors: CinexColors = DarkCinexColors,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                CinexTheme(colors) {
                    Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun hallState(hallId: Long, select: ((HallDetailViewModel) -> Unit)? = null): HallDetailUiState {
        val vm = HallDetailViewModel(hallId, repository, repository)
        select?.invoke(vm)
        return runBlocking { vm.uiState.first { it !is HallDetailUiState.Loading } }
    }

    @Composable
    private fun HallDetail(state: HallDetailUiState) {
        HallDetailScreen(state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {}, onSeatClick = {}, onSeatChosen = {})
    }

    @Test
    fun hallDetail() {
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1)
        capture("hall_detail") { HallDetail(state) }
    }

    @Test
    fun hallDetailWithSelectedSeat() {
        val seat = SampleLayouts.splitHall.rows[6].seatIds[9] // row 7, centre block
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1) { it.onSeatClick(seat) }
        check((state as HallDetailUiState.Loaded).selectedSeat?.label == SeatLabel("7", 6))
        capture("hall_detail_selected_seat") { HallDetail(state) }
    }

    /** The visit history below the map, on a tall screen so it is fully visible. */
    @Test
    @Config(qualifiers = "w412dp-h1400dp-port-night-xxhdpi")
    fun hallDetailWithVisitHistory() {
        val rowG = SampleLayouts.splitHall.rows[6].seatIds
        val visits = listOf(
            Visit(
                id = 9_001, hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 10, 3),
                filmTitle = "Perfect Days", seatId = rowG[13], screeningFormat = ScreeningFormat.OMU, seatRating = Rating(4),
            ),
            Visit(
                id = 9_002, hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 9, 19),
                filmTitle = "The Brutalist", seatId = rowG[2], screeningFormat = ScreeningFormat.SEVENTY_MM, seatRating = Rating(5),
            ),
            Visit(id = 9_003, hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 8, 30), seatRating = Rating(2)),
        )
        repository.store.value = repository.store.value.copy(visits = visits)
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1)
        capture("hall_detail_visit_history") { HallDetail(state) }
    }

    /**
     * The approved mockup: coloured map (great, OK, avoid, no data, favourite), legend,
     * the selected favourite seat, scores and visits, on a tall screen.
     */
    @Test
    @Config(qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
    fun hallDetailRatedTall() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL) { it.onSeatClick(RatedHallFixture.favourite) }
        capture("hall_detail_rated_tall") { HallDetail(state) }
    }

    /**
     * Seat numbers on every numbered seat of the coloured map (spec §6.3), at phone
     * size: the selected avoid seat shows its × above its number, the favourite its ★.
     */
    @Test
    fun hallDetailWithNumbers() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL) { it.onSeatClick(RatedHallFixture.manualAvoid) }
        capture("hall_detail_numbers") { HallDetail(state) }
    }

    /** The same at phone size: what is visible without scrolling. */
    @Test
    fun hallDetailRated() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL)
        capture("hall_detail_rated") { HallDetail(state) }
    }

    /**
     * Contrast fix for the owner to judge (M7): the rated hall with "no data" seats as
     * before (dark fill only, 1.5:1 against the card) and as now (with the lighter rim,
     * 3.6:1). Same map and legend in both.
     */
    @Test
    fun noDataSeatContrastBeforeAndAfter() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL)
        val before = DarkCinexColors.copy(seat = DarkCinexColors.seat.copy(noDataOutline = DarkCinexColors.seat.noData))
        capture("seat_contrast_before", colors = before) { HallDetail(state) }
    }

    @Test
    fun noDataSeatContrastAfter() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL)
        capture("seat_contrast_after") { HallDetail(state) }
    }

    /** A seat set to "avoid" manually: the Avoid button is marked active. */
    @Test
    @Config(qualifiers = "w412dp-h1300dp-port-night-xxhdpi")
    fun hallDetailManualOverride() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL) { it.onSeatClick(RatedHallFixture.manualAvoid) }
        capture("hall_detail_manual_override") { HallDetail(state) }
    }

    /** Large font sizes: the colour buttons wrap into two rows, labels and scores stay whole. */
    @Test
    @Config(qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
    fun hallDetailFontScale150() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL) { it.onSeatClick(RatedHallFixture.manualAvoid) }
        capture("hall_detail_font_150", fontScale = 1.5f) { HallDetail(state) }
    }

    @Test
    @Config(qualifiers = "w412dp-h2800dp-port-night-xxhdpi")
    fun hallDetailFontScale200() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL) { it.onSeatClick(RatedHallFixture.favourite) }
        capture("hall_detail_font_200", fontScale = 2f) { HallDetail(state) }
    }

    /** A drawn hall without any visits: grey map, "–" scores. */
    @Test
    @Config(qualifiers = "w412dp-h1500dp-port-night-xxhdpi")
    fun hallDetailNoRatingsYet() {
        repository.store.value = repository.store.value.let { s ->
            s.copy(visits = s.visits.filter { it.hallId != SampleData.DELPHI_LUX_SAAL_1 })
        }
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1) { it.onSeatClick(RatedHallFixture.noData) }
        capture("hall_detail_no_ratings") { HallDetail(state) }
    }

    @Test
    fun hallDetailEmpty() {
        val state = hallState(SampleData.DELPHI_LUX_SAAL_6)
        capture("hall_detail_empty") { HallDetail(state) }
    }

    @Test
    fun hallDetailUnreadable() {
        repository.store.value = repository.store.value.copy(
            halls = repository.store.value.halls.map {
                if (it.id == SampleData.DELPHI_LUX_SAAL_6) it.copy(layoutUnreadable = true) else it
            },
        )
        val state = hallState(SampleData.DELPHI_LUX_SAAL_6)
        capture("hall_detail_unreadable") { HallDetail(state) }
    }

    private val noActions = object : LayoutEditorActions {}

    @Test
    fun layoutEditorWithSelectedRow() {
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(3) // the staggered row 4
        val state = vm.uiState.value
        capture("layout_editor_row_selected") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    /** The selected screen (spec §6.5): green bar and handles, the screen panel in place of the row panel. */
    @Test
    fun layoutEditorWithSelectedScreen() {
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectScreen()
        val state = vm.uiState.value
        check(state.screenSelected)
        capture("layout_editor_screen_selected") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    /**
     * The selected screen moved 3 seats to the left (spec §4.2, §6.5): the bar is drawn
     * there, the label and the "Screen position" stepper say so.
     */
    @Test
    @Config(qualifiers = "w412dp-h1100dp-port-night-xxhdpi")
    fun layoutEditorWithScreenOffset() {
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectScreen()
        vm.changeScreenWidth(-4f)
        vm.changeScreenOffset(-3f)
        val state = vm.uiState.value
        check(state.draft?.screenOffset == -3f)
        capture("layout_editor_screen_offset") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    /**
     * Seat numbers as Yorck has them ([SampleLayouts.yorckNumbering]): wheelchair spaces
     * numbered with their symbol above the number (row 1: great, OK, avoid, no data),
     * rows right to left, and the selected favourite "R" in row 6: "ROW 6 · SEAT R".
     */
    @Test
    @Config(qualifiers = "w412dp-h1300dp-port-night-xxhdpi")
    fun hallDetailYorckNumbering() {
        repository = FakeCinexRepository(SampleData.storeWithYorckNumbering())
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1) { it.onSeatClick(SampleLayouts.yorckRowSixR) }
        check((state as HallDetailUiState.Loaded).selectedSeat?.label == SeatLabel("6", "R", SeatKind.WHEELCHAIR))
        capture("hall_detail_yorck_numbering") { HallDetail(state) }
    }

    /**
     * The row panel of a right-to-left row in three blocks (row 4 of
     * [SampleLayouts.yorckNumbering]): "Right → left" selected, the "Starts at"
     * fields in numbering order (right block first: 1–3, 4–10, 11–13).
     */
    @Test
    @Config(qualifiers = "w412dp-h1300dp-port-night-xxhdpi")
    fun layoutEditorRightToLeftRow() {
        repository = FakeCinexRepository(SampleData.storeWithYorckNumbering())
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(3)
        val state = vm.uiState.value
        check(state.row?.blocksInNumberingOrder?.map { it.rangeText } == listOf("1–3", "4–10", "11–13"))
        capture("layout_editor_right_to_left") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    /**
     * The row with the fixed "R" (row 6, right to left, the "R" at the right end)
     * selected in the editor: the "R" on its seat, the range "R + 1–14" in numbering order.
     */
    @Test
    @Config(qualifiers = "w412dp-h1300dp-port-night-xxhdpi")
    fun layoutEditorFixedLabelRow() {
        repository = FakeCinexRepository(SampleData.storeWithYorckNumbering())
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(5)
        val state = vm.uiState.value
        check(state.row?.blocks?.single()?.rangeText == "R + 1–14")
        capture("layout_editor_fixed_label") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    /** The hall detail of a hall whose screen is 3 seats left of centre: the map and the viewing angle follow it. */
    @Test
    fun hallDetailWithScreenOffset() {
        val store = RatedHallFixture.store()
        repository = FakeCinexRepository(
            store.copy(halls = store.halls.map { if (it.id == RatedHallFixture.HALL) it.copy(screenOffset = -3f) else it }),
        )
        val state = hallState(RatedHallFixture.HALL) { it.onSeatClick(RatedHallFixture.favourite) }
        check((state as HallDetailUiState.Loaded).screenOffset == -3f)
        capture("hall_detail_screen_offset") { HallDetail(state) }
    }

    /**
     * Zoomed in by the user: the seat numbers are bigger, and the "Fit" control sits in
     * the line under the canvas, where it never covers the screen or its handles.
     */
    @Test
    fun layoutEditorZoomedIn() {
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectScreen()
        val state = vm.uiState.value
        val viewState = SeatMapViewState().apply {
            // Twice the zoom of the fitted view, on the screen's right end (px at xxhdpi).
            userTransform = ViewTransform(scale = 2f, offset = Offset(-1146f, 384f))
        }
        capture("layout_editor_adjusted") {
            LayoutEditorScreen(state, referenceImage = null, actions = noActions, viewState = viewState)
        }
    }

    /**
     * The editor state of an invented hall [columns] wide (three blocks, 14 rows) with a
     * screen [screenWidth] seats wide, as opened on a phone; [selected] selects the screen.
     */
    private fun wideHallEditor(columns: Int, screenWidth: Float, selected: Boolean): LayoutEditorUiState {
        val side = (columns - 2) / 4
        val row = "S".repeat(side) + "|" + "S".repeat(columns - 2 - 2 * side) + "|" + "S".repeat(side)
        val layout = SeatLayout.fromPatterns(List(14) { row }, firstSeatId = 1)
        val hall = Hall(
            id = WIDE_HALL,
            cinemaId = SampleData.DELPHI_LUX,
            name = "Saal 9",
            layout = layout,
            screenWidth = screenWidth,
            screenDistance = 3f,
            nextSeatId = layout.seatCount + 1,
        )
        repository.store.value = repository.store.value.let { it.copy(halls = it.halls + hall) }
        val vm = LayoutEditorViewModel(WIDE_HALL, repository, FakeReferenceImageStore())
        if (selected) vm.selectScreen()
        val state = vm.uiState.value
        check(state.draft?.columnCount == columns && state.screenSelected == selected)
        return state
    }

    /**
     * Wide halls at the fitted zoom (S1): the label above the screen keeps its room on
     * screen however small the grid is drawn, so it is never cut off and stays touchable.
     */
    @Test
    fun layoutEditorWide40() {
        val state = wideHallEditor(columns = 40, screenWidth = 30f, selected = false)
        capture("layout_editor_wide_40") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    @Test
    fun layoutEditorWide40Selected() {
        val state = wideHallEditor(columns = 40, screenWidth = 30f, selected = true)
        capture("layout_editor_wide_40_selected") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    /** The widest grid the editor allows, its screen as wide as the grid (handles at the outer edges). */
    @Test
    fun layoutEditorWide60() {
        val state = wideHallEditor(columns = LayoutDraft.MAX_COLUMNS, screenWidth = 60f, selected = false)
        capture("layout_editor_wide_60") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    @Test
    fun layoutEditorWide60Selected() {
        val state = wideHallEditor(columns = LayoutDraft.MAX_COLUMNS, screenWidth = 60f, selected = true)
        capture("layout_editor_wide_60_selected") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
    }

    @Test
    fun layoutEditorWithReferenceScreenshot() {
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.onReferenceImagePicked("content://test/plan.png")
        val state = vm.uiState.value
        check(state.referenceMode && state.draft?.referenceImagePath != null)
        val image = testHallPlan().asImageBitmap()
        capture("layout_editor_reference") { LayoutEditorScreen(state, referenceImage = image, actions = noActions) }
    }

    private companion object {
        const val WIDE_HALL = 9_100L
    }

    /** A generated stand-in for a Yorck-app seat plan screenshot: light background, rows of grey seats. */
    private fun testHallPlan(): Bitmap {
        val bitmap = Bitmap.createBitmap(540, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF2F2F2.toInt())
        val seat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E8E.toInt() }
        val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt() }
        canvas.drawRoundRect(RectF(110f, 30f, 430f, 44f), 7f, 7f, screen)
        val pitch = 22f
        for (row in 0 until 10) {
            val shift = if (row == 3) pitch / 2 else 0f
            for (column in 0 until 22) {
                if (column == 4 || column == 17) continue // aisles
                val x = 28f + column * pitch + shift
                val y = 100f + row * 28f
                canvas.drawRoundRect(RectF(x, y, x + 17f, y + 17f), 4f, 4f, seat)
            }
        }
        return bitmap
    }
}
