package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
                Column {
                    // The canvas that is dragged, and below it one that shows the same draft
                    // untouched: always at the fitted height of the draft as it is.
                    for (tag in listOf(DRAGGED, REFERENCE)) {
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
                            modifier = Modifier.fillMaxWidth().testTag(tag),
                        )
                    }
                }
            }
        }
        val canvas = composeRule.onNodeWithTag(DRAGGED)
        val reference = composeRule.onNodeWithTag(REFERENCE)
        val before = canvas.fetchSemanticsNode().size
        val density = composeRule.density
        val room = with(density) { HANDLE_ROOM.toPx() }
        // The screen is the widest thing, so at the fitted zoom its handles sit HANDLE_ROOM from
        // the canvas's edges, on the bar's line: that gives the fitted zoom.
        val pitchX = editorMetrics(density, labelWidth = 0f).pitchX
        val fitScale = (before.width - 2f * room) / (14f * pitchX)
        val handle = with(density) { Offset(before.width - room, SCREEN_LABEL_HEIGHT.toPx() + 5.dp.toPx()) }
        val move = with(density) { -40.dp.toPx() }

        canvas.performTouchInput {
            down(handle)
            moveBy(Offset(move, 0f))
        }
        composeRule.waitForIdle()
        assertEquals(1, gestures)
        assertEquals("the screen's width follows the finger", ScreenRules.width(14f + 2f * move / (pitchX * fitScale), 7), draft.screenWidth, 0f)
        val fittedNow = reference.fetchSemanticsNode().size.height
        assertTrue("the fitted height grows: $fittedNow", fittedNow > before.height)
        assertEquals("the canvas's height during the drag", before.height, canvas.fetchSemanticsNode().size.height)

        canvas.performTouchInput { up() }
        composeRule.waitForIdle()
        // Released: the canvas settles to the narrower content's fitted height.
        assertEquals(reference.fetchSemanticsNode().size.height, canvas.fetchSemanticsNode().size.height)
    }

    private companion object {
        const val DRAGGED = "dragged"
        const val REFERENCE = "reference"
    }
}
