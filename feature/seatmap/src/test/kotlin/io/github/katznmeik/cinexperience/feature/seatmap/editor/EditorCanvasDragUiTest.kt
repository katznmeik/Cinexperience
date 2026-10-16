package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.seatmapui.rememberSeatMapViewState
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The editor canvas during a drag of the selected screen's handle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class EditorCanvasDragUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theCanvasKeepsItsHeightWhileAHandleIsDragged() {
        // A small hall with a screen twice as wide as its grid: dragging a handle inward
        // narrows the content, so the fitted zoom (and with it the canvas's fitted height) grows.
        val layout = SeatLayout.fromPatterns(List(5) { "S".repeat(7) }, firstSeatId = 1)
        var draft by mutableStateOf(LayoutDraft.start(Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = layout, screenWidth = 14f, screenDistance = 1.5f)))
        var gestures = 0
        val actions = object : LayoutEditorActions {
            override fun onBeginGesture() {
                gestures++
            }

            override fun onScreenWidth(width: Float) {
                draft = draft.withScreenWidth(width)
            }
        }
        composeRule.setContent {
            CinexTheme {
                EditorCanvas(
                    draft = draft,
                    labels = draft.layout.labels(),
                    selectedRow = null,
                    referenceMode = false,
                    referenceImage = null,
                    referenceOpacity = 0.4f,
                    referenceAlignment = null,
                    actions = actions,
                    viewState = rememberSeatMapViewState(),
                    screenSelected = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val canvas = composeRule.onNodeWithContentDescription("Layout canvas", substring = true)
        val before = canvas.fetchSemanticsNode().size
        val density = composeRule.density
        // The right handle sits HANDLE_ROOM from the canvas's right edge at the fitted zoom, on the bar's line.
        val handle = with(density) { Offset(before.width - HANDLE_ROOM.toPx(), SCREEN_LABEL_HEIGHT.toPx() + 5.dp.toPx()) }
        val move = with(density) { Offset(-40.dp.toPx(), 0f) }

        canvas.performTouchInput {
            down(handle)
            moveBy(move)
        }
        composeRule.waitForIdle()
        assertEquals(1, gestures)
        assertTrue("the drag narrowed the screen: ${draft.screenWidth}", draft.screenWidth < 14f)
        assertEquals("the canvas's height during the drag", before.height, canvas.fetchSemanticsNode().size.height)

        canvas.performTouchInput { up() }
        composeRule.waitForIdle()
        // Released: the canvas may settle to the narrower content's fitted height.
        assertTrue(canvas.fetchSemanticsNode().size.height >= before.height)
    }
}
