package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMap
import io.github.katznmeik.cinexperience.core.seatmapui.seatNumbersShownWhenFitted
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every preloaded hall exactly as the app seeds it: a fresh [DataContainer]
 * seeds its database from the real seed asset, and the hall is drawn with the
 * app's [SeatMap]. One PNG per hall in feature/seatmap/build/outputs/roborazzi
 * (`yorck_seed_<cinema>__<hall>.png`), for comparing with the yorck.de seat plans
 * the layouts were generated from. The device is as wide as a phone but very
 * tall, so that even the longest halls are drawn whole at the width-fitted zoom.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class YorckSeedScreenshotTest(private val seedKey: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun halls(): List<Array<Any>> = listOf(
            "babylon-kreuzberg/saal-1", "babylon-kreuzberg/saal-2",
            "blauer-stern/saal-1", "blauer-stern/saal-2",
            "capitol-dahlem/saal-1",
            "cinema-paris/saal-1",
            "delphi-filmpalast/saal-1",
            "delphi-lux/saal-1", "delphi-lux/saal-2", "delphi-lux/saal-3", "delphi-lux/saal-4",
            "delphi-lux/saal-5", "delphi-lux/saal-6", "delphi-lux/saal-7",
            "filmtheater-am-friedrichshain/saal-1", "filmtheater-am-friedrichshain/saal-2",
            "filmtheater-am-friedrichshain/saal-3", "filmtheater-am-friedrichshain/saal-4",
            "filmtheater-am-friedrichshain/saal-5",
            "international/saal-1",
            "kant-kino/saal-1", "kant-kino/saal-2", "kant-kino/saal-3", "kant-kino/saal-4", "kant-kino/saal-5",
            "neues-off/saal-1",
            "odeon/saal-1",
            "passage/saal-1", "passage/saal-2", "passage/saal-3", "passage/saal-4",
            "rollberg/saal-1", "rollberg/saal-2", "rollberg/saal-3", "rollberg/saal-4", "rollberg/saal-5",
            "yorck/yorck", "yorck/new-yorck",
        ).map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun cancelScope() {
        scope.cancel()
    }

    private fun seededHall(): Pair<String, Hall> = runBlocking {
        val data = DataContainer(ApplicationProvider.getApplicationContext(), scope)
        val cinema = data.cinemaRepository.observeCinemaDetails().first().single { detail ->
            detail.halls.any { it.hall.seedKey == seedKey }
        }
        val id = cinema.halls.single { it.hall.seedKey == seedKey }.hall.id
        cinema.cinema.name to data.hallRepository.getHall(id)!!
    }

    @Test
    fun capture() {
        val (cinemaName, hall) = seededHall()
        val layout = checkNotNull(hall.layout) { "$seedKey has no seed layout" }
        val kinds = layout.rows.flatMap { it.cells }.filterIsInstance<LayoutCell.Seat>().groupingBy { it.kind }.eachCount()
        val summary = "${layout.seatCount} places · ${kinds[SeatKind.WHEELCHAIR] ?: 0} W · " +
            "${kinds[SeatKind.COMPANION] ?: 0} C · ${kinds[SeatKind.SOFA] ?: 0} F · " +
            "screen ${hall.screenWidth} × ${hall.screenDistance}"
        composeRule.setContent {
            // Numbers at the fitted zoom in the hall detail's map (412 dp phone: 376 dp wide).
            val density = LocalDensity.current
            val width = with(density) { 376.dp.toPx() }
            val numbers = if (seatNumbersShownWhenFitted(layout, width, density, hall.screenWidth, hall.screenOffset)) {
                "numbers when fitted"
            } else {
                "numbers after zooming in"
            }
            CinexTheme {
                Column(
                    Modifier.fillMaxSize().background(CinexTheme.colors.background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("$cinemaName · ${hall.name}".uppercase(), style = CinexTheme.textStyles.screenTitle, color = CinexTheme.colors.text)
                    Text("$summary · ${layout.columnCount} columns · $numbers", color = CinexTheme.colors.textMuted)
                    SeatMap(
                        layout = layout,
                        contentDescription = hall.name,
                        screenWidth = hall.screenWidth,
                        screenDistance = hall.screenDistance,
                        screenOffset = hall.screenOffset,
                        maxHeight = 2200.dp,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/yorck_seed_${seedKey.replace("/", "__")}.png")
    }
}
