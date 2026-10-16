package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.seatmap.editor.EditorDialog
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorActions
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorScreen
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The layout editor's dialogs at font scales 1, 1.5 and 2, with the TalkBack
 * checks. The font scale is set through the configuration (not
 * ScreenshotSurface), so the dialog window gets it too; the clock is held, so
 * the dialog's animations can't keep Compose busy. The screen captures include
 * every window.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
class EditorDialogsAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, dialog: EditorDialog) {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val state = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore()).uiState.value.copy(dialog = dialog)
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ScreenshotSurface { LayoutEditorScreen(state, referenceImage = null, actions = object : LayoutEditorActions {}) }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        if (fontScale != 1f) captureScreenRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun discard() = check("editor_discard_dialog", EditorDialog.ConfirmDiscard)

    @Test
    fun confirmSave() = check(
        "editor_confirm_save_dialog",
        EditorDialog.ConfirmSave(
            removedSeats = listOf(SeatLabel("7", 10), SeatLabel("8", "R", SeatKind.WHEELCHAIR)),
            replacesUnreadableLayout = false,
        ),
    )

    @Test
    fun saveFailed() = check("editor_save_failed_dialog", EditorDialog.SaveFailed("Couldn't save the layout. Please try again.", canReload = true))
}
