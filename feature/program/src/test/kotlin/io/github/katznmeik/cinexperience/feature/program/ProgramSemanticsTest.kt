package io.github.katznmeik.cinexperience.feature.program

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.ui.text.dayHeaderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.katznmeik.cinexperience.feature.program.ProgramScreenFixtures as F

/** What TalkBack gets from the film page and the saved-filters dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class ProgramSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The day labels under each cinema ("Today · Thursday 15 Oct") are headings, so TalkBack can jump between days. */
    @Test
    fun filmPageDayLabelsAreHeadings() {
        val state = F.film()
        val firstDay = (state.content as FilmContent.Loaded).cinemas.first().days.first().date
        composeRule.setContent { ScreenshotSurface { FilmScreen(state, FilmActions.None) } }
        composeRule.waitForIdle()
        val label = composeRule.onAllNodesWithText(dayHeaderText(firstDay, F.today))
        assertTrue(label.fetchSemanticsNodes().isNotEmpty())
        label.assertAll(isHeading())
    }

    /** A saved filter reads "double tap to apply filter" and applies itself when tapped. */
    @Test
    fun savedFilterEntriesNameTheirAction() {
        val applied = mutableListOf<String>()
        val state = F.state().copy(
            savedFilters = listOf(SavedFilter("After work", F.busyFilter)),
            dialog = ProgramDialog.SAVED_FILTERS,
        )
        val actions = object : ProgramActions {
            override fun onApplySavedFilter(name: String) {
                applied += name
            }
        }
        // The clock is held, so the dialog's animations can't keep Compose busy.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface { ProgramScreen(state, actions) } }
        composeRule.mainClock.advanceTimeBy(2_000)
        val entry = composeRule.onNodeWithText("After work")
        assertEquals("Apply filter", entry.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
        entry.performClick()
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(listOf("After work"), applied)
    }
}
