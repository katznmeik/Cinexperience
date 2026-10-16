package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorActions
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorScreen
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The layout editor's reference panel (spec §6.5), with and without a
 * screenshot, at font scales 1, 1.5 and 2, plus the basic TalkBack checks of
 * [assertAccessible]. PNGs: `layout_editor_reference[_empty]_font_150/200.png`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
class ReferencePanelAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val noActions = object : LayoutEditorActions {}

    private fun editor() =
        LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, FakeCinexRepository(SampleData.storeWithSplitHall()), FakeReferenceImageStore())

    private fun check(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface(fontScale) { content() } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun withScreenshot() {
        val vm = editor()
        vm.onReferenceImagePicked("content://test/plan.png")
        val state = vm.uiState.value
        check(state.referenceMode && state.draft?.referenceImagePath != null)
        val image = ReferencePlanFixture.hallPlan().asImageBitmap()
        check("layout_editor_reference") { LayoutEditorScreen(state, referenceImage = image, actions = noActions) }
        composeRule.onNodeWithContentDescription("Screenshot opacity").assertExists()
        // The opacity value ("40 %") grows with the text and never wraps.
        val value = composeRule.onNodeWithText("40 %").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        value.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
    }

    @Test
    fun withoutScreenshot() {
        val vm = editor()
        vm.toggleReferenceMode()
        val state = vm.uiState.value
        check(state.referenceMode && state.draft?.referenceImagePath == null)
        check("layout_editor_reference_empty") { LayoutEditorScreen(state, referenceImage = null, actions = noActions) }
        composeRule.onNodeWithText("Choose screenshot").assertExists()
    }
}
