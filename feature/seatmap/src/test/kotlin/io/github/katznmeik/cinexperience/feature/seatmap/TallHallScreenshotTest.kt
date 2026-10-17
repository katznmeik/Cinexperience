package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailScreen
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailUiState
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hall detail's map with preloaded halls exactly as seeded (spec §6.3): a tall,
 * narrow hall (Kant Kino Saal 3, 11 double sofas) is shown whole, as tall as the
 * window allows, and an ordinary wide hall stays fitted to the width. The page is
 * scrolled so that the map is in view, as the user would. PNGs land in
 * feature/seatmap/build/outputs/roborazzi (`hall_detail_tall_*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TallHallScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun cancelScope() {
        scope.cancel()
    }

    /** The hall [seedKey] as the app seeds it (a fresh [DataContainer] seeds from the real asset). */
    private fun seededHall(seedKey: String): Hall = runBlocking {
        val data = DataContainer(ApplicationProvider.getApplicationContext(), scope, appVersion = "1.2.3")
        val id = data.cinemaRepository.observeCinemaDetails().first()
            .flatMap { it.halls }
            .single { it.hall.seedKey == seedKey }
            .hall.id
        data.hallRepository.getHall(id)!!
    }

    /** The hall detail of the fixture hall [hallId] with [seedKey]'s seeded layout and screen, no ratings yet. */
    private fun captureSeeded(seedKey: String, hallId: Long, name: String) {
        val seeded = seededHall(seedKey)
        val store = SampleData.store().let { s ->
            s.copy(
                halls = s.halls.map {
                    if (it.id != hallId) it else it.copy(
                        layout = seeded.layout,
                        screenWidth = seeded.screenWidth,
                        screenDistance = seeded.screenDistance,
                        screenOffset = seeded.screenOffset,
                        nextSeatId = seeded.nextSeatId,
                    )
                },
                // A freshly seeded hall: no visits, colours or favourite (grey map).
                visits = s.visits.filter { it.hallId != hallId },
                favourites = s.favourites - hallId,
                overrides = s.overrides.filter { it.hallId != hallId },
            )
        }
        val repository = FakeCinexRepository(store)
        val state = runBlocking {
            HallDetailViewModel(hallId, repository, repository).uiState.first { it !is HallDetailUiState.Loading }
        }
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    HallDetailScreen(state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {}, onSeatClick = {}, onSeatChosen = {})
                }
            }
        }
        val map = composeRule.onNode(hasContentDescription("Seat map of", substring = true))
        map.performScrollTo()
        composeRule.waitForIdle()
        // The whole map fits into the window: every row is in view without panning.
        val window = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = map.fetchSemanticsNode().boundsInRoot
        assertTrue("map $bounds inside window $window", bounds.top >= window.top && bounds.bottom <= window.bottom)
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Phone (915 dp): the map may be 715 dp tall, enough for Saal 3 at the width-fitted zoom. */
    @Test
    fun kantKinoSaal3() {
        captureSeeded("kant-kino/saal-3", SampleData.KANT_KINO_SAAL_3, "hall_detail_tall_kant_kino_saal_3")
    }

    /** A short window (640 dp): the map keeps 520 dp, and Saal 3 is fitted to that height, centred. */
    @Test
    @Config(qualifiers = "w412dp-h640dp-port-night-xxhdpi")
    fun kantKinoSaal3ShortWindow() {
        captureSeeded("kant-kino/saal-3", SampleData.KANT_KINO_SAAL_3, "hall_detail_tall_kant_kino_saal_3_short_window")
    }

    /** An ordinary wide hall (Delphi Lux Saal 1): fitted to the width as before, its height is small anyway. */
    @Test
    fun wideHall() {
        captureSeeded("delphi-lux/saal-1", SampleData.DELPHI_LUX_SAAL_1, "hall_detail_tall_wide_delphi_lux_saal_1")
    }
}
