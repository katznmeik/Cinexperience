package io.github.katznmeik.cinexperience.core.data

import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.repository.HallLimitReachedException
import io.github.katznmeik.cinexperience.core.model.DataLimits.MAX_HALLS
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `createHall` stops at [MAX_HALLS] halls in all cinemas together, the backup
 * validator's cap, so whatever the app saves can be exported.
 */
internal class HallLimitTest : DatabaseTest() {

    /** [count] halls spread over two cinemas, written straight into the table. */
    private suspend fun fillHalls(count: Int): Pair<Long, Long> {
        val a = cinemas.createCinema("Kino A")
        val b = cinemas.createCinema("Kino B")
        db.withTransaction {
            repeat(count) { i ->
                db.hallDao().insert(
                    HallEntity(
                        cinemaId = if (i % 2 == 0) a else b, name = "Saal ${i + 1}", layoutJson = null, screenWidth = null,
                        screenDistance = null, referenceImagePath = null, notes = null, isPreloaded = i % 3 == 0, seedKey = null,
                    ),
                )
            }
        }
        return a to b
    }

    @Test
    fun theLastHallUpToTheCapIsCreated() = runTest {
        val (a, _) = fillHalls(MAX_HALLS - 1)
        halls.createHall(a, "Saal 1000")
        assertEquals(MAX_HALLS, db.hallDao().getAll().size)
    }

    @Test
    fun aHallBeyondTheCapIsRefusedWithAMessage() = runTest {
        val (a, b) = fillHalls(MAX_HALLS)
        for (cinema in listOf(a, b)) {
            val e = assertThrows(HallLimitReachedException::class.java) { runBlocking { halls.createHall(cinema, "One more") } }
            assertEquals("The app holds at most 1,000 halls. Delete a hall to add a new one.", e.message)
        }
        assertEquals(MAX_HALLS, db.hallDao().getAll().size)

        // Deleting one makes room again.
        val userHall = db.hallDao().getAll().first { !it.isPreloaded }
        halls.deleteHall(userHall.id)
        halls.createHall(a, "One more")
        assertEquals(MAX_HALLS, db.hallDao().getAll().size)
    }
}
