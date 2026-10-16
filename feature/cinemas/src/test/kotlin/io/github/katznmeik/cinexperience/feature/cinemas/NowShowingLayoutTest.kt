package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.feature.cinemas.detail.NowShowing
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Now showing": day labels are headings, and a row stacks its time above the film with large text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class NowShowingLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val showings = NowShowing.of(
        ProgramState(permission = DownloadPermission.ALLOWED, program = NowShowingFixtures.program, isLoaded = true),
        "delphi-lux",
        NowShowingFixtures.now,
        NowShowingFixtures.today,
    )

    private fun show(fontScale: Float) {
        composeRule.setContent { ScreenshotSurface(fontScale) { DetailWith(showings) } }
        composeRule.waitForIdle()
    }

    /** Bounds of the (unmerged) text [text] of the first showing (today, 16:30). */
    private fun bounds(text: String): Rect =
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot

    @Test
    fun dayLabelsAreHeadings() {
        show(fontScale = 1f)
        for (day in listOf("Today · Thursday 15 Oct", "Tomorrow · Friday 16 Oct", "Saturday 17 Oct", "Wednesday 21 Oct")) {
            composeRule.onNodeWithText(day).assert(isHeading())
        }
    }

    @Test
    fun timeBesideTheFilm() {
        show(fontScale = 1f)
        val time = bounds("16:30")
        val title = bounds("The Quiet Projectionist")
        assertTrue("time $time left of the title $title", time.right <= title.left)
        assertTrue("same line", time.top < title.bottom && title.top < time.bottom)
    }

    @Test
    fun timeAboveTheFilmWithLargeText() {
        show(fontScale = 2f)
        val time = bounds("16:30")
        val title = bounds("The Quiet Projectionist")
        assertTrue("time $time above the title $title", time.bottom <= title.top)
        assertTrue("title starts under the time", title.left <= time.left + 1f)
    }
}
