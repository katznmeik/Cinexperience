package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditDialog
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditUiState
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The visit form's dialogs, date picker and photo menu at font scales 1, 1.5
 * and 2, with the TalkBack checks. The font scale is set through the
 * configuration (not ScreenshotSurface), so the dialog and menu windows get it
 * too; the clock is held, so their animations can't keep Compose busy. The
 * screen captures include every window.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class VisitDialogsAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }

        /** Material's date picker reads its month header and its year-switch button with the same text. */
        private val MONTH_HEADER = setOf("october 2026")
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun form(mode: VisitEditMode): VisitEditUiState {
        val repository = FakeCinexRepository(VisitFixtures.store())
        val photos = FakeVisitPhotoStore(setOf("photos/visit-fixture.jpg"))
        return VisitEditViewModel(mode, repository, repository, photos, SavedStateHandle(), today = { LocalDate.of(2026, 10, 14) }).uiState.value
    }

    /** Shows [state], then [open]s a window on it (a click, or nothing when [state] has a dialog). */
    private fun check(name: String, state: VisitEditUiState, allowRepeated: Set<String> = emptySet(), open: () -> Unit = {}) {
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) } }
        composeRule.mainClock.advanceTimeBy(2_000)
        open()
        composeRule.mainClock.advanceTimeBy(2_000)
        if (fontScale != 1f) captureScreenRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible(allowRepeated = allowRepeated)
    }

    @Test
    fun discardDialog() = check("visit_discard_dialog", form(VisitEditMode.Add(null)).copy(dialog = VisitEditDialog.ConfirmDiscard))

    @Test
    fun deleteDialog() =
        check("visit_delete_dialog", form(VisitEditMode.Edit(VisitFixtures.EDITED_VISIT)).copy(dialog = VisitEditDialog.ConfirmDelete))

    @Test
    fun datePicker() = check("visit_date_picker", form(VisitEditMode.Add(null)), allowRepeated = MONTH_HEADER) {
        composeRule.onNode(hasContentDescription("Date, ", substring = true)).performClick()
    }

    @Test
    fun photoSourceMenu() = check("visit_photo_menu", form(VisitEditMode.Add(null))) {
        composeRule.onNodeWithText("Add photo").performClick()
    }
}
