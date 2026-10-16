package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.data.repository.HallLimitReachedException
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.DataLimits.MAX_HALLS
import io.github.katznmeik.cinexperience.core.model.Hall
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The fake's `createHall` stops at [MAX_HALLS] halls like Room's (see `HallLimitTest` in core:data). */
class FakeHallLimitTest {

    private fun repository(hallCount: Int) = FakeCinexRepository(
        FakeStore(
            cinemas = listOf(Cinema(id = 1, name = "Kino"), Cinema(id = 2, name = "Other")),
            halls = List(hallCount) { i -> Hall(id = 10L + i, cinemaId = 1L + i % 2, name = "Saal ${i + 1}") },
        ),
    )

    @Test
    fun theLastHallUpToTheCapIsCreated() = runTest {
        val repo = repository(MAX_HALLS - 1)
        repo.createHall(2, "Saal 1000")
        assertEquals(MAX_HALLS, repo.store.value.halls.size)
    }

    @Test
    fun aHallBeyondTheCapIsRefusedWithAMessage() = runTest {
        val repo = repository(MAX_HALLS)
        val e = assertThrows(HallLimitReachedException::class.java) { runBlocking { repo.createHall(2, "One more") } }
        assertEquals(HallLimitReachedException.MESSAGE, e.message)
        assertEquals(MAX_HALLS, repo.store.value.halls.size)
    }
}
