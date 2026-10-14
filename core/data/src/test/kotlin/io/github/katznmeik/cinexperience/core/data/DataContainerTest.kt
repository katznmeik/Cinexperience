package io.github.katznmeik.cinexperience.core.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataContainerTest {

    @Test
    fun firstEmissionAlreadyContainsTheShippedSeed() = runTest {
        val data = DataContainer(ApplicationProvider.getApplicationContext(), backgroundScope)
        val cinemas = data.cinemaRepository.observeCinemas().first()
        assertEquals(14, cinemas.size)
        assertTrue(cinemas.all { it.cinema.isPreloaded && it.cinema.address != null })
        val yorck = cinemas.single { it.cinema.seedKey == "yorck" }
        val detail = data.cinemaRepository.observeCinemaDetail(yorck.cinema.id).first()!!
        assertEquals(listOf("New Yorck", "Yorck"), detail.halls.map { it.hall.name })
    }
}
