package io.github.katznmeik.cinexperience.feature.rankings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRankingsRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the Rankings tab with the illustrative [SampleData], phone-sized,
 * dark theme. PNGs land in feature/rankings/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class RankingsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: RankingsUiState) {
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    RankingsScreen(state, onScopeChange = {}, onCategoryChange = {}, onOpenItem = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val rankings = runBlocking {
        FakeCinexRepository(SampleData.store()).let { CinemaRankingsRepository(it, it) }.observeRankings().first()
    }

    @Test
    fun hallsBySound() {
        val state = RankingsUiState(isLoading = false, hallCategory = HallRankingCategory.SOUND)
        capture(
            "rankings_halls_sound",
            state.copy(items = rankedItems(rankings, state.scope, state.hallCategory, state.cinemaCategory)),
        )
    }

    @Test
    fun cinemasOverall() {
        val state = RankingsUiState(isLoading = false, scope = RankingScope.CINEMAS)
        capture(
            "rankings_cinemas_overall",
            state.copy(items = rankedItems(rankings, state.scope, state.hallCategory, state.cinemaCategory)),
        )
    }

    @Test
    fun empty() {
        capture("rankings_empty", RankingsUiState(isLoading = false, nothingRated = true))
    }
}
