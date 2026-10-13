package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class SeederTest : DatabaseTest() {

    private val v1 = """
        {"seedVersion": 1, "cinemas": [
          {"seedKey": "alpha", "name": "Alpha", "address": null, "halls": [
            {"seedKey": "alpha/1", "name": "Saal 1"}
          ]},
          {"seedKey": "beta", "name": "Beta", "address": "Beta Street 1", "halls": []}
        ]}
    """

    private val v2 = """
        {"seedVersion": 2, "cinemas": [
          {"seedKey": "alpha", "name": "Alpha (verified)", "address": "Alpha Street 2", "halls": [
            {"seedKey": "alpha/1", "name": "Saal One", "screenWidth": 10, "screenDistance": 2,
             "layout": {"formatVersion": 1, "rows": [{"cells": "SSS"}]}},
            {"seedKey": "alpha/2", "name": "Saal 2"}
          ]},
          {"seedKey": "beta", "name": "Beta", "address": "Other Street", "halls": []},
          {"seedKey": "gamma", "name": "Gamma"}
        ]}
    """

    private fun seeder(json: String) = Seeder(db) { json }

    @Test
    fun firstRunInsertsPreloadedItems() = runTest {
        assertTrue(seeder(v1).seedIfNeeded())
        val all = db.cinemaDao().getAll()
        assertEquals(listOf("Alpha", "Beta"), all.map { it.name }.sorted())
        assertTrue(all.all { it.isPreloaded })
        val hall = db.hallDao().getAll().single()
        assertEquals("alpha/1", hall.seedKey)
        assertTrue(hall.isPreloaded)
        assertEquals(all.single { it.seedKey == "alpha" }.id, hall.cinemaId)
    }

    @Test
    fun runningTwiceCreatesNoDuplicates() = runTest {
        assertTrue(seeder(v1).seedIfNeeded())
        assertFalse(seeder(v1).seedIfNeeded())
        assertEquals(2, db.cinemaDao().getAll().size)
        assertEquals(1, db.hallDao().getAll().size)
    }

    @Test
    fun olderSeedVersionDoesNothing() = runTest {
        seeder(v2).seedIfNeeded()
        assertFalse(seeder(v1).seedIfNeeded())
        assertEquals(3, db.cinemaDao().getAll().size)
    }

    @Test
    fun newerSeedAddsItemsWithoutOverwritingUserEdits() = runTest {
        seeder(v1).seedIfNeeded()
        val alpha = db.cinemaDao().getAll().single { it.seedKey == "alpha" }
        val beta = db.cinemaDao().getAll().single { it.seedKey == "beta" }
        cinemas.renameCinema(alpha.id, "My Alpha")
        cinemas.updateCinema(beta.id, "Beta", address = "My address", notes = "my notes")
        val userHall = halls.createHall(alpha.id, "Saal 1") // user-added hall, same name as a seed hall

        assertTrue(seeder(v2).seedIfNeeded())

        val all = db.cinemaDao().getAll()
        assertEquals(3, all.size)
        val alphaAfter = all.single { it.seedKey == "alpha" }
        assertEquals("My Alpha", alphaAfter.name) // rename kept
        assertEquals("Alpha Street 2", alphaAfter.address) // was null → filled
        val betaAfter = all.single { it.seedKey == "beta" }
        assertEquals("My address", betaAfter.address)
        assertEquals("my notes", betaAfter.notes)
        assertTrue(all.single { it.seedKey == "gamma" }.isPreloaded)

        val hallsAfter = db.hallDao().getAll()
        assertEquals(3, hallsAfter.size)
        val seeded1 = hallsAfter.single { it.seedKey == "alpha/1" }
        assertEquals("Saal 1", seeded1.name) // name never overwritten
        assertEquals(SeatLayout.fromPatterns("SSS"), LayoutJson.decode(seeded1.layoutJson!!)) // empty layout filled
        assertEquals(10f, seeded1.screenWidth)
        assertEquals(alphaAfter.id, hallsAfter.single { it.seedKey == "alpha/2" }.cinemaId)
        assertNull(hallsAfter.single { it.id == userHall }.seedKey)
    }

    @Test
    fun newerSeedDoesNotReplaceADrawnLayout() = runTest {
        seeder(v1).seedIfNeeded()
        val hall = halls.getHall(db.hallDao().getAll().single().id)!!
        val drawn = SeatLayout.fromPatterns("S.S")
        halls.updateHall(hall.copy(layout = drawn, screenWidth = 3f))

        seeder(v2).seedIfNeeded()

        val after = halls.getHall(hall.id)!!
        assertEquals(drawn, after.layout)
        assertEquals(3f, after.screenWidth)
        assertEquals(2f, after.screenDistance) // was still empty
    }

    @Test
    fun seededDataIsVisibleThroughRepositories() = runTest {
        seeder(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText()).seedIfNeeded()
        val list = cinemas.observeCinemas().first()
        assertEquals(13, list.size)
        assertEquals("Babylon Kreuzberg", list.first().cinema.name)
        assertTrue(list.all { it.visitCount == 0 && it.overallScore == null })
        val lux = list.single { it.cinema.seedKey == "delphi-lux" }
        val detail = cinemas.observeCinemaDetail(lux.cinema.id).first()!!
        assertEquals((1..7).map { "Saal $it" }, detail.halls.map { it.hall.name })
    }
}
