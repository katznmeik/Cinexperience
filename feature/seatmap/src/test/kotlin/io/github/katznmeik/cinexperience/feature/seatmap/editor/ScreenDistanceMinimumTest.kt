package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.ScreenSizeRules
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapGeometry
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The editor sets screen distances of at least half a row (N22): at 0 the bar lies
 * on row 0, where only its upper part can be touched (the screen controls never
 * reach into row 0). The data rules still allow 0, so a stored 0 loads and stays
 * until the distance is changed.
 */
class ScreenDistanceMinimumTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val layout = SeatLayout.fromPatterns("SSSS", "SSSS", firstSeatId = 1)
    private fun hall(distance: Float) = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = layout, screenWidth = 4f, screenDistance = distance)

    private fun viewModel(distance: Float): LayoutEditorViewModel {
        val store = FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall(distance)))
        return LayoutEditorViewModel(1, FakeCinexRepository(store), FakeReferenceImageStore())
    }

    private val LayoutEditorViewModel.distance get() = uiState.value.draft!!.screenDistance

    @Test
    fun editsStopHalfARowInFrontOfRowOne() {
        assertEquals(0.5f, ScreenRules.MIN_DISTANCE)
        val draft = LayoutDraft.start(hall(2f))
        assertEquals(0.5f, draft.withScreenDistance(0f).screenDistance)
        assertEquals(0.5f, draft.withScreenDistance(-3f).screenDistance)
        assertEquals(0.5f, draft.withScreenDistance(0.2f).screenDistance)
        assertEquals(1f, draft.withScreenDistance(1.1f).screenDistance)
        assertEquals(15f, draft.withScreenDistance(40f).screenDistance)
        // The data rules are unchanged: 0 is still a valid stored distance.
        assertEquals(0f, ScreenSizeRules.MIN_DISTANCE)
        assertTrue(ScreenSizeRules.isValidDistance(0f))
    }

    @Test
    fun aStoredZeroLoadsAndStaysUntilTheDistanceIsChanged() {
        val draft = LayoutDraft.start(hall(0f))
        assertEquals(0f, draft.screenDistance)
        // A step or drag towards row 0 doesn't make the screen jump away from it.
        assertEquals(0f, draft.withScreenDistance(-0.5f).screenDistance)
        assertEquals(0f, draft.withScreenDistance(0.1f).screenDistance)
        // Once moved away, it stays at least half a row away.
        val moved = draft.withScreenDistance(0.5f)
        assertEquals(0.5f, moved.screenDistance)
        assertEquals(0.5f, moved.withScreenDistance(0f).screenDistance)
    }

    @Test
    fun theStepperStopsAtHalfARow() {
        val vm = viewModel(1f)
        vm.changeScreenDistance(-0.5f)
        assertEquals(0.5f, vm.distance)
        vm.changeScreenDistance(-0.5f) // no further: and no undo step for it
        assertEquals(0.5f, vm.distance)
        vm.undo()
        assertEquals(1f, vm.distance)
        assertFalse(vm.uiState.value.canUndo)
    }

    @Test
    fun aDragFromAStoredZero() {
        val vm = viewModel(0f)
        assertEquals(0f, vm.distance)
        assertFalse(vm.uiState.value.hasUnsavedChanges)
        vm.beginGesture()
        vm.dragScreenDistance(-0.4f) // towards the seats: stays
        assertEquals(0f, vm.distance)
        vm.dragScreenDistance(1.2f)
        assertEquals(1f, vm.distance)
        vm.dragScreenDistance(0f) // back: half a row in front of row 1
        assertEquals(0.5f, vm.distance)
        vm.endGesture()
        vm.undo()
        assertEquals(0f, vm.distance)
    }

    /**
     * At the editor's minimum distance the whole bar can be touched, at the fitted
     * zoom of halls up to the widest grid; at 0 its middle line lies in row 0's band.
     */
    @Test
    fun atHalfARowTheWholeBarIsAboveRowZero() {
        val metrics = editorMetrics(Density(1f), labelWidth = 22f)
        val canvasWidth = 388f
        // Half the selected bar's stroke (5 dp), just inside its lower edge.
        val halfBar = 2.4f
        for (columns in listOf(1, 16, 30, SeatLayout.MAX_COLUMNS)) {
            val grid = SeatLayout.fromPatterns(List(10) { "S".repeat(columns) }, firstSeatId = 1)
            for (distance in listOf(ScreenRules.MIN_DISTANCE, 0f)) {
                val g = SeatMapGeometry(grid, metrics, columns.toFloat(), distance)
                val t = editorViewBounds(Size(canvasWidth, 300f), g, maxZoomScale = 3f, labelHeight = 34f, handleRoom = 17.5f).fitted()
                val left = t.toScreen(Offset(g.screenLeft, g.screenY))
                val right = t.toScreen(Offset(g.screenRight, g.screenY))
                val label = screenLabelRect((left.x + right.x) / 2f, left.y, Size(120f, 14f), gap = 9f, canvasWidth = canvasWidth, dp = 1f)
                val middle = Offset((left.x + right.x) / 2f, left.y)
                fun hit(point: Offset, selected: Boolean) = hitTest(point, g, t, handleTouchRadius = 24f, label = label, screenSelected = selected)
                val case = "$columns columns, distance $distance"
                if (distance == 0f) {
                    // On row 0: the bar's middle line paints the cell under it.
                    assertEquals(case, CanvasHit.Cell(CellPosition(0, columns / 2)), hit(middle, selected = false))
                    continue
                }
                for (selected in listOf(false, true)) {
                    assertEquals(case, CanvasHit.ScreenBar, hit(middle, selected))
                    assertEquals(case, CanvasHit.ScreenBar, hit(middle + Offset(0f, halfBar), selected))
                    assertEquals(case, CanvasHit.ScreenBar, hit(middle - Offset(0f, halfBar), selected))
                }
            }
        }
    }
}
