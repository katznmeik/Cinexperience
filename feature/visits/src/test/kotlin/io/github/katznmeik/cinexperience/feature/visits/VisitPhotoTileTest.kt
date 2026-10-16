package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The "Add photo" tile is at least 96 dp and grows with its text: "Add photo"
 * stays on one line inside it at every font scale.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class VisitPhotoTileTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = listOf(1f, 1.5f, 2f).map { arrayOf(it) }
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun addPhotoFitsItsTile() {
        val repository = FakeCinexRepository(VisitFixtures.store())
        val state = VisitEditViewModel(VisitEditMode.Add(null), repository, repository, FakeVisitPhotoStore(), SavedStateHandle()) {
            LocalDate.of(2026, 10, 14)
        }.uiState.value
        composeRule.setContent { ScreenshotSurface(fontScale) { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) } }
        composeRule.waitForIdle()

        val tile = composeRule.onNodeWithText("Add photo").performScrollTo().fetchSemanticsNode()
        val density = tile.layoutInfo.density
        val minPx = with(density) { 96.dp.toPx() } - 0.5f
        assertTrue("tile ${tile.size}", tile.size.width >= minPx && tile.size.height >= minPx)

        val text = composeRule.onNodeWithText("Add photo", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("\"Add photo\" on one line", 1, layouts.single().lineCount)
        val inside = tile.boundsInRoot
        val label = text.boundsInRoot
        assertTrue("$label inside $inside", label.left >= inside.left && label.right <= inside.right && label.bottom <= inside.bottom)
    }
}
