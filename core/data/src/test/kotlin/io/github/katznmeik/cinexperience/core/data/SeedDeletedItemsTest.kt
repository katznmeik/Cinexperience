package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A cinema or hall the user deleted stays deleted: a later seed version doesn't
 * bring it back. Halls the seeder adds to a cinema the user created stay
 * deletable, like the cinema.
 */
internal class SeedDeletedItemsTest : DatabaseTest() {

    private fun seeder(json: String) = Seeder(db) { json }

    /** Alpha (with Saal 1) and Gamma; [gammaHalls] and [alphaHall2] grow with later versions. */
    private fun seed(version: Int, gammaHalls: Int = 1, alphaHall2: Boolean = false): String {
        val gamma = (1..gammaHalls).joinToString(",") { """{"seedKey": "gamma/$it", "name": "Saal $it"}""" }
        val alpha2 = if (alphaHall2) """, {"seedKey": "alpha/2", "name": "Saal 2"}""" else ""
        return """
            {"seedVersion": $version, "cinemas": [
              {"seedKey": "alpha", "name": "Alpha", "halls": [
                {"seedKey": "alpha/1", "name": "Saal 1", "layout": {"formatVersion": 1, "rows": [{"cells": "SSS"}]}}$alpha2
              ]},
              {"seedKey": "gamma", "name": "Gamma", "address": "Gamma Street 1", "halls": [$gamma]}
            ]}
        """
    }

    private suspend fun cinemaKeys() = db.cinemaDao().getAll().mapNotNull { it.seedKey }.toSet()

    private suspend fun hallKeys() = db.hallDao().getAll().mapNotNull { it.seedKey }.toSet()

    @Test
    fun seedHallsOfAnAdoptedCinemaAreDeletableAndTheDeletedCinemaStaysDeleted() = runTest {
        val gammaId = cinemas.createCinema("Gamma") // typed in by hand before the seed had it
        assertTrue(seeder(seed(1)).seedIfNeeded())

        val gamma = db.cinemaDao().get(gammaId)!!
        assertEquals("gamma", gamma.seedKey)
        assertFalse(gamma.isPreloaded)
        val gammaHall = db.hallDao().getAll().single { it.seedKey == "gamma/1" }
        assertEquals(gammaId, gammaHall.cinemaId)
        assertFalse(gammaHall.isPreloaded) // like its cinema

        assertEquals(DeleteResult.DELETED, cinemas.deleteCinema(gammaId))
        assertTrue(seeder(seed(2, gammaHalls = 2)).seedIfNeeded())

        // Neither the cinema nor its halls come back, not even a hall the seed just added.
        assertEquals(setOf("alpha"), cinemaKeys())
        assertEquals(setOf("alpha/1"), hallKeys())
        // The snapshot rows stay: they are the record that Gamma was there.
        assertTrue(db.seedSnapshotDao().getAll().any { it.kind == "cinema" && it.seedKey == "gamma" })
        assertTrue(db.seedSnapshotDao().getAll().any { it.kind == "hall" && it.seedKey == "gamma/1" })
    }

    @Test
    fun aSeedHallOfAnAdoptedCinemaCanBeDeletedOnItsOwnAndStaysDeleted() = runTest {
        val gammaId = cinemas.createCinema("Gamma")
        seeder(seed(1)).seedIfNeeded()
        val gammaHall = db.hallDao().getAll().single { it.seedKey == "gamma/1" }

        assertEquals(DeleteResult.DELETED, halls.deleteHall(gammaHall.id))
        seeder(seed(2, gammaHalls = 2)).seedIfNeeded()

        // The deleted hall stays deleted; a new seed hall still joins the cinema, deletable too.
        val gammaHalls = db.hallDao().getAll().filter { it.cinemaId == gammaId }
        assertEquals(listOf("gamma/2"), gammaHalls.map { it.seedKey })
        assertFalse(gammaHalls.single().isPreloaded)
    }

    @Test
    fun aDeletedAdoptedHallStaysDeleted() = runTest {
        seeder(seed(1)).seedIfNeeded()
        val alpha = db.cinemaDao().getAll().single { it.seedKey == "alpha" }
        val mine = halls.createHall(alpha.id, "Saal 2") // typed in by hand before the seed had it
        seeder(seed(2, alphaHall2 = true)).seedIfNeeded()
        val adopted = db.hallDao().get(mine)!!
        assertEquals("alpha/2", adopted.seedKey)
        assertFalse(adopted.isPreloaded)

        assertEquals(DeleteResult.DELETED, halls.deleteHall(mine))
        assertTrue(seeder(seed(3, alphaHall2 = true)).seedIfNeeded())

        assertEquals(setOf("alpha/1", "gamma/1"), hallKeys())
        assertTrue(db.hallDao().getAll().all { it.isPreloaded }) // the seeded ones are untouched
    }

    @Test
    fun aDeletedCinemaCreatedAgainByHandIsAdoptedAgainWithoutItsDeletedHalls() = runTest {
        cinemas.createCinema("Gamma")
        seeder(seed(1)).seedIfNeeded()
        cinemas.deleteCinema(db.cinemaDao().getAll().single { it.seedKey == "gamma" }.id)
        val again = cinemas.createCinema("Gamma", address = null)

        seeder(seed(2, gammaHalls = 2)).seedIfNeeded()

        val gamma = db.cinemaDao().get(again)!!
        assertEquals("gamma", gamma.seedKey) // linked again, so the program finds it
        assertFalse(gamma.isPreloaded)
        assertNull(gamma.address) // the seed's address is unchanged since it was last supplied
        val gammaHalls = db.hallDao().getAll().filter { it.cinemaId == again }
        assertEquals(listOf("gamma/2"), gammaHalls.map { it.seedKey }) // gamma/1 was deleted with the cinema
        assertFalse(gammaHalls.single().isPreloaded)
    }

    @Test
    fun hallsOfAPreloadedCinemaArePreloaded() = runTest {
        seeder(seed(1)).seedIfNeeded()
        seeder(seed(2, gammaHalls = 2, alphaHall2 = true)).seedIfNeeded()

        val all = db.hallDao().getAll()
        assertEquals(setOf("alpha/1", "alpha/2", "gamma/1", "gamma/2"), all.mapNotNull { it.seedKey }.toSet())
        assertTrue(all.all { it.isPreloaded })
        assertTrue(db.cinemaDao().getAll().all { it.isPreloaded })
    }

    @Test
    fun aFreshInstallSeedsEveryCinemaAndHall() = runTest {
        val asset = File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText()
        assertTrue(seeder(asset).seedIfNeeded())

        val all = db.cinemaDao().getAll()
        assertEquals(14, all.size)
        assertTrue(all.all { it.isPreloaded })
        val allHalls = db.hallDao().getAll()
        assertEquals(38, allHalls.size)
        assertTrue(allHalls.all { it.isPreloaded })

        // A later seed version with the same items changes nothing.
        val version = Regex("\"seedVersion\"\\s*:\\s*(\\d+)").find(asset)!!.groupValues[1].toInt()
        assertTrue(seeder(asset.replaceFirst(Regex("\"seedVersion\"\\s*:\\s*\\d+"), "\"seedVersion\": ${version + 1}")).seedIfNeeded())
        assertEquals(all, db.cinemaDao().getAll())
        assertEquals(allHalls, db.hallDao().getAll())
    }
}
