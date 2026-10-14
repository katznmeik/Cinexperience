package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
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
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The delphi LUX halls exactly as the app seeds them: a fresh [DataContainer]
 * seeds its database from the real seed asset, and each hall is drawn with the
 * app's [SeatMap]. One PNG per hall in feature/seatmap/build/outputs/roborazzi,
 * for comparing with the Yorck-app screenshots the layouts were transcribed from.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class DelphiLuxSeedScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun cancelScope() {
        scope.cancel()
    }

    private fun seededHall(seedKey: String): Hall = runBlocking {
        val data = DataContainer(ApplicationProvider.getApplicationContext(), scope)
        val lux = data.cinemaRepository.observeCinemaDetails().first().single { it.cinema.seedKey == "delphi-lux" }
        val id = lux.halls.single { it.hall.seedKey == seedKey }.hall.id
        data.hallRepository.getHall(id)!!
    }

    private fun capture(seedKey: String, file: String) {
        val hall = seededHall(seedKey)
        val layout = checkNotNull(hall.layout) { "$seedKey has no seed layout" }
        val kinds = layout.rows.flatMap { it.cells }.filterIsInstance<LayoutCell.Seat>().groupingBy { it.kind }.eachCount()
        val summary = "${layout.seatCount} places · ${kinds[SeatKind.WHEELCHAIR] ?: 0} W · " +
            "${kinds[SeatKind.COMPANION] ?: 0} C · ${kinds[SeatKind.SOFA] ?: 0} F · " +
            "screen ${hall.screenWidth} × ${hall.screenDistance}"
        composeRule.setContent {
            CinexTheme {
                Column(
                    Modifier.fillMaxSize().background(CinexTheme.colors.background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(hall.name.uppercase(), style = CinexTheme.textStyles.screenTitle, color = CinexTheme.colors.text)
                    Text(summary, color = CinexTheme.colors.textMuted)
                    SeatMap(
                        layout = layout,
                        contentDescription = hall.name,
                        screenWidth = hall.screenWidth,
                        screenDistance = hall.screenDistance,
                        maxHeight = 760.dp,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/delphi_lux_$file.png")
    }

    @Test
    fun saal1() = capture("delphi-lux/saal-1", "saal_1")

    @Test
    fun saal2() = capture("delphi-lux/saal-2", "saal_2")

    @Test
    fun saal3() = capture("delphi-lux/saal-3", "saal_3")

    @Test
    fun saal4() = capture("delphi-lux/saal-4", "saal_4")

    @Test
    fun saal5() = capture("delphi-lux/saal-5", "saal_5")

    @Test
    fun saal6() = capture("delphi-lux/saal-6", "saal_6")

    @Test
    fun luxbox() = capture("delphi-lux/saal-7", "luxbox")
}
