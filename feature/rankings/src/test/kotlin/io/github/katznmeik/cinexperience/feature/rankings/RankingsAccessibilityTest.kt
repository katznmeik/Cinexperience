package io.github.katznmeik.cinexperience.feature.rankings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRankingsRepository
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Rankings tab at large font scales (tall captures), plus the basic TalkBack checks of [assertAccessible]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class RankingsAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(name: String, state: RankingsUiState) {
        composeRule.setContent {
            ScreenshotSurface(fontScale) { RankingsScreen(state, onScopeChange = {}, onCategoryChange = {}, onOpenItem = {}) }
        }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    private val rankings = runBlocking {
        FakeCinexRepository(SampleData.store()).let { CinemaRankingsRepository(it, it) }.observeRankings().first()
    }

    @Test
    fun hallsBySound() {
        val state = RankingsUiState(isLoading = false, hallCategory = HallRankingCategory.SOUND)
        check("rankings_halls_sound", state.copy(items = rankedItems(rankings, state.scope, state.hallCategory, state.cinemaCategory)))
    }

    @Test
    fun empty() {
        check("rankings_empty", RankingsUiState(isLoading = false, nothingRated = true))
    }
}
