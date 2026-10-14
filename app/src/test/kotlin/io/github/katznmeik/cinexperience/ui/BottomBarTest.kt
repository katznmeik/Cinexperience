package io.github.katznmeik.cinexperience.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.CinemaDetailRoute
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bottom bar while it slides away with an opening detail (spec §7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class BottomBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aTabTapWhileTheBarSlidesAwayIsIgnored() {
        val container = FakeAppContainer(FakeCinexRepository(SampleData.store()))
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            CinexTheme { CinexApp(container, navController = navController) }
        }
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("DELPHI LUX").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        // The bar is still on screen, sliding out: a tap on Rankings must not switch tabs.
        composeRule.onNodeWithTag(TopLevelDestination.Rankings.testTag).performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        val current = navController.currentBackStackEntry?.destination
        assertTrue("Expected the cinema detail, was ${current?.route}", current?.hasRoute<CinemaDetailRoute>() == true)
    }
}
