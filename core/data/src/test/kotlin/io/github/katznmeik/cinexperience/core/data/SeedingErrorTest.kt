package io.github.katznmeik.cinexperience.core.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A seeding run that fails, even with an `Error` such as `OutOfMemoryError`, is
 * logged and leaves the app usable: the repositories still load (without the
 * seed), instead of every screen showing "Couldn't load" until a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeedingErrorTest {

    private fun data(scope: CoroutineScope, failure: Throwable) = DataContainer(
        ApplicationProvider.getApplicationContext(),
        scope,
        "1.2.3",
        Dispatchers.Default,
        Dispatchers.IO,
        seedSource = { throw failure },
    )

    @Test
    fun anErrorWhileSeedingStillLetsTheRepositoriesLoad() = runTest {
        val data = data(backgroundScope, OutOfMemoryError("test"))
        assertTrue(data.cinemaRepository.observeCinemas().first().isEmpty())
        assertEquals(emptyList<Any>(), data.visitRepository.observeAllVisits().first())

        // Writes work too, and what they write is read back.
        val id = data.cinemaRepository.createCinema("Kino")
        assertEquals(listOf("Kino"), data.cinemaRepository.observeCinemas().first().map { it.cinema.name })
        assertEquals(id, data.cinemaRepository.observeCinemaDetail(id).first()!!.cinema.id)
    }

    @Test
    fun anExceptionWhileSeedingStillLetsTheRepositoriesLoad() = runTest {
        val data = data(backgroundScope, IllegalStateException("broken seed"))
        assertTrue(data.cinemaRepository.observeCinemas().first().isEmpty())
    }
}
