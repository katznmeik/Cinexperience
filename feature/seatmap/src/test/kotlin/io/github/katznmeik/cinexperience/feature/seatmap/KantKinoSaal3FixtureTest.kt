package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screenshots of Kant Kino Saal 3 use [SampleLayouts.kantKinoSaal3]; it must be
 * the layout the app seeds (a fresh [DataContainer] seeds from the real asset).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KantKinoSaal3FixtureTest {

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun cancelScope() {
        scope.cancel()
    }

    @Test
    fun theFixtureIsTheSeededLayout() = runBlocking {
        val data = DataContainer(ApplicationProvider.getApplicationContext(), scope, appVersion = "1.2.3")
        val hall = data.cinemaRepository.observeCinemaDetails().first()
            .flatMap { it.halls }
            .single { it.hall.seedKey == "kant-kino/saal-3" }
            .hall
        val seeded = data.hallRepository.getHall(hall.id)!!
        assertEquals(SampleLayouts.kantKinoSaal3, seeded.layout)
        assertEquals(5f, seeded.screenWidth)
        assertEquals(1.5f, seeded.screenDistance)
    }
}
