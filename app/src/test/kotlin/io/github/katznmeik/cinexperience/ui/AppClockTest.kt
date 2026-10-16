package io.github.katznmeik.cinexperience.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens that read the time get it from the app container: the app tests run
 * at [FakeAppContainer]'s fixed "now" (SampleProgram.FETCHED_AT, 15 Oct 2026 10:00 in
 * Berlin), not on the day they happen to run. Kant Kino's only sample showing,
 * 15 Oct 20:45, is then still to come.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class AppClockTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nowShowingAndTheProgramTabUseTheContainersClock() {
        val container = FakeAppContainer(FakeCinexRepository(SampleData.freshInstallStore()), programRepository = FakeProgramRepository.withProgram())
        composeRule.setContent { CinexTheme { CinexApp(container) } }

        composeRule.onNodeWithText("KANT KINO").performClick()
        waitForText(FILM) // "Now showing" on the cinema's page

        composeRule.onNodeWithText("Show all in Program").performScrollTo().performClick()
        waitForText("\u2068Kant Kino\u2069 · Any time · All versions")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { textNodes("Show all in Program").isEmpty() } // the cinema's page is gone
        waitForText(FILM) // a result of the Program tab
    }

    /** The screens build their state off the main thread, which Compose's idling doesn't wait for. */
    private fun waitForText(text: String) = composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { textNodes(text).isNotEmpty() }

    private fun textNodes(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()

    private companion object {
        const val FILM = "The Quiet Projectionist"
        const val TIMEOUT_MS = 10_000L
    }
}
