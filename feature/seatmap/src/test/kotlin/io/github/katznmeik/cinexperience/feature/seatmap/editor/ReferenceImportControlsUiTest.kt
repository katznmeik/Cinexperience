package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * While a reference screenshot is still being copied, Save is disabled and
 * "Replace" starts no second import, but Discard stays available (spec §6.5).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
class ReferenceImportControlsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun saveAndReplaceWaitForTheImportDiscardDoesNot() {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val loaded = LayoutEditorViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, FakeReferenceImageStore()).uiState.value
        val withScreenshot = loaded.copy(draft = loaded.draft!!.withReferenceImage("reference/hall-1-a.jpg"), referenceMode = true)
        var state by mutableStateOf(withScreenshot.copy(isImportingReference = true))
        var picks = 0
        var discards = 0
        val actions = object : LayoutEditorActions {
            override fun onPickReference() {
                picks++
            }

            override fun onDiscard() {
                discards++
            }
        }
        composeRule.setContent { ScreenshotSurface { LayoutEditorScreen(state, referenceImage = null, actions = actions) } }

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Loading…").performClick() // the "Replace" button while importing
        assertEquals(0, picks)
        composeRule.onNodeWithText("Discard").assertIsEnabled().performClick()
        assertEquals(1, discards)

        state = withScreenshot
        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Replace").performClick()
        assertEquals(1, picks)
    }
}
