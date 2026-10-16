package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsScreen
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The seat-rating stars of a visit row grow with the font size; with large text
 * they move under the row's texts, so those keep the width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class VisitStarsLargeTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** [starsWidth] and [expectedWidth]: five stars of 13 sp at the font scale, 1 dp apart. */
    private class Row(val title: Rect, val stars: Rect, val starsWidth: Dp, val expectedWidth: Dp)

    /** "Perfect Days", rated 4 stars. */
    private fun show(fontScale: Float): Row {
        val state = runBlocking { AllVisitsViewModel(FakeCinexRepository(VisitFixtures.store())).uiState.first { !it.isLoading } }
        composeRule.setContent { ScreenshotSurface(fontScale) { AllVisitsScreen(state, onBack = {}, onOpenVisit = {}) } }
        composeRule.waitForIdle()
        val stars = composeRule.onNodeWithContentDescription("Rated 4 stars", useUnmergedTree = true).fetchSemanticsNode()
        val title = composeRule.onNodeWithText("Perfect Days", substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val density = stars.layoutInfo.density
        val width = with(density) { stars.size.width.toDp() }
        // Android scales large sp sizes a little less than linearly: 13 sp at 2× is 25 dp.
        val star = with(Density(density.density, fontScale)) { 13.sp.toDp() }
        return Row(title.boundsInRoot, stars.boundsInRoot, width, star * 5 + 4.dp)
    }

    @Test
    fun besideTheTextsAtTheDefaultSize() {
        val row = show(1f)
        assertEquals(69f, row.starsWidth.value, 1f) // 5 × 13 dp and 4 × 1 dp
        assertTrue("stars ${row.stars} right of the title ${row.title}", row.stars.left >= row.title.right)
    }

    @Test
    fun largerAndUnderTheTextsWithLargeText() {
        val row = show(2f)
        assertTrue("grown: ${row.starsWidth}", row.starsWidth > 120.dp)
        assertEquals(row.expectedWidth.value, row.starsWidth.value, 1f)
        assertTrue("stars ${row.stars} under the title ${row.title}", row.stars.top >= row.title.bottom)
        assertEquals(row.title.left, row.stars.left, 1f)
    }
}
