package io.github.katznmeik.cinexperience.feature.seatmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
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

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) { content() }
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
    private fun hallDetail(state: HallDetailUiState) {
        HallDetailScreen(state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {}, onSeatClick = {}, onSeatChosen = {})
    }

    @Test
    fun hallDetail() {
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1)
        capture("hall_detail") { hallDetail(state) }
    }

    @Test
    fun hallDetailWithSelectedSeat() {
        val seat = SampleLayouts.splitHall.rows[6].seatIds[9] // row 7, centre block
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1) { it.onSeatClick(seat) }
        check((state as HallDetailUiState.Loaded).selectedSeat?.label == SeatLabel("7", 6))
        capture("hall_detail_selected_seat") { hallDetail(state) }
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
        capture("hall_detail_visit_history") { hallDetail(state) }
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
        capture("hall_detail_rated_tall") { hallDetail(state) }
    }

    /** The same at phone size: what is visible without scrolling. */
    @Test
    fun hallDetailRated() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL)
        capture("hall_detail_rated") { hallDetail(state) }
    }

    /** A seat set to "avoid" manually: the Avoid button is marked active. */
    @Test
    @Config(qualifiers = "w412dp-h1300dp-port-night-xxhdpi")
    fun hallDetailManualOverride() {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val state = hallState(RatedHallFixture.HALL) { it.onSeatClick(RatedHallFixture.manualAvoid) }
        capture("hall_detail_manual_override") { hallDetail(state) }
    }

    /** A drawn hall without any visits: grey map, "–" scores. */
    @Test
    @Config(qualifiers = "w412dp-h1500dp-port-night-xxhdpi")
    fun hallDetailNoRatingsYet() {
        repository.store.value = repository.store.value.let { s ->
            s.copy(visits = s.visits.filter { it.hallId != SampleData.DELPHI_LUX_SAAL_1 })
        }
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1) { it.onSeatClick(RatedHallFixture.noData) }
        capture("hall_detail_no_ratings") { hallDetail(state) }
    }

    @Test
    fun hallDetailEmpty() {
        val state = hallState(SampleData.DELPHI_LUX_SAAL_6)
        capture("hall_detail_empty") { hallDetail(state) }
    }

    @Test
    fun hallDetailUnreadable() {
        repository.store.value = repository.store.value.copy(
            halls = repository.store.value.halls.map {
                if (it.id == SampleData.DELPHI_LUX_SAAL_6) it.copy(layoutUnreadable = true) else it
            },
        )
        val state = hallState(SampleData.DELPHI_LUX_SAAL_6)
        capture("hall_detail_unreadable") { hallDetail(state) }
    }

    private val noActions = object : LayoutEditorActions {}

    @Test
    fun layoutEditorWithSelectedRow() {
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(3) // the staggered row 4
        val state = vm.uiState.value
        capture("layout_editor_row_selected") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
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
