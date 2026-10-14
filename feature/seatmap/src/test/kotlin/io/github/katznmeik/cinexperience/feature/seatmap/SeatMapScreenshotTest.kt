package io.github.katznmeik.cinexperience.feature.seatmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
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
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatMap
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatTone
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatVisual
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())

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
        val vm = HallDetailViewModel(hallId, repository)
        select?.invoke(vm)
        return runBlocking { vm.uiState.first { it !is HallDetailUiState.Loading } }
    }

    @Composable
    private fun hallDetail(state: HallDetailUiState) {
        HallDetailScreen(state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onSeatClick = {}, onSeatChosen = {})
    }

    @Test
    fun hallDetail() {
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1)
        capture("hall_detail") { hallDetail(state) }
    }

    @Test
    fun hallDetailWithSelectedSeat() {
        val seat = SampleLayouts.splitHall.rows[6].seatIds[9] // row G, centre block
        val state = hallState(SampleData.DELPHI_LUX_SAAL_1) { it.onSeatClick(seat) }
        check((state as HallDetailUiState.Loaded).selectedSeat?.label == SeatLabel("G", 6))
        capture("hall_detail_selected_seat") { hallDetail(state) }
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

    /**
     * The visual states M4 will use (colours, favourite, avoid ×) on standard seats
     * (top row) and on wheelchair, companion and sofa seats (bottom rows).
     */
    @Test
    fun seatMapStates() {
        val layout = SeatLayout.fromPatterns("SSSS|SSSS", "WCFS|WCFW", "WCF.|.FCW", firstSeatId = 1)
        val tones = listOf(SeatTone.NO_DATA, SeatTone.GREAT, SeatTone.OK, SeatTone.AVOID)
        val visuals = layout.seatIds.withIndex().associate { (index, id) ->
            id to SeatVisual(tones[index % 4], isFavourite = index in setOf(4, 5, 13, 14))
        }
        capture("seat_map_states") {
            SeatMap(
                layout = layout,
                contentDescription = "States",
                screenWidth = 6f,
                seatVisuals = visuals,
                selectedSeatId = SeatId(3),
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    private val noActions = object : LayoutEditorActions {}

    @Test
    fun layoutEditorWithSelectedRow() {
        val vm = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore())
        vm.selectRow(3) // the staggered row D
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
