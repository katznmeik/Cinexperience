package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

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
        assertEquals(layoutOf("SSS"), LayoutJson.decode(seeded1.layoutJson!!)) // empty layout filled
        assertEquals(10f, seeded1.screenWidth)
        assertEquals(alphaAfter.id, hallsAfter.single { it.seedKey == "alpha/2" }.cinemaId)
        assertNull(hallsAfter.single { it.id == userHall }.seedKey)
    }

    @Test
    fun newerSeedDoesNotReplaceADrawnLayout() = runTest {
        seeder(v1).seedIfNeeded()
        val hall = halls.getHall(db.hallDao().getAll().single().id)!!
        val drawn = layoutOf("S.S")
        halls.updateHall(hall.copy(layout = drawn, screenWidth = 3f))

        seeder(v2).seedIfNeeded()

        val after = halls.getHall(hall.id)!!
        assertEquals(drawn, after.layout)
        assertEquals(3f, after.screenWidth)
        assertEquals(2f, after.screenDistance) // was still empty
    }

    private fun seedWith(version: Int, address: String?, layoutCells: String?) = """
        {"seedVersion": $version, "cinemas": [
          {"seedKey": "alpha", "name": "Alpha", "address": ${address?.let { "\"$it\"" } ?: "null"}, "halls": [
            {"seedKey": "alpha/1", "name": "Saal 1"
             ${layoutCells?.let { """, "layout": {"formatVersion": 1, "rows": [{"cells": "$it"}]}""" } ?: ""}}
          ]}
        ]}
    """

    @Test
    fun valueClearedByTheUserIsNotRefilledWithTheSameSeedValue() = runTest {
        seeder(seedWith(1, "Street 1", "SSS")).seedIfNeeded()
        val cinemaId = db.cinemaDao().getAll().single().id
        val hall = halls.getHall(db.hallDao().getAll().single().id)!!
        cinemas.updateCinema(cinemaId, "Alpha", address = null, notes = null)
        halls.updateHall(hall.copy(layout = null))

        seeder(seedWith(2, "Street 1", "SSS")).seedIfNeeded() // same values again
        assertNull(db.cinemaDao().get(cinemaId)!!.address)
        assertNull(halls.getHall(hall.id)!!.layout)

        seeder(seedWith(3, "Street 2", "SSSS")).seedIfNeeded() // corrected values
        assertEquals("Street 2", db.cinemaDao().get(cinemaId)!!.address)
        assertEquals(4, halls.getHall(hall.id)!!.layout!!.seatCount)
    }

    private fun seedWithOffset(version: Int, offset: Float?) = """
        {"seedVersion": $version, "cinemas": [
          {"seedKey": "alpha", "name": "Alpha", "halls": [
            {"seedKey": "alpha/1", "name": "Saal 1" ${offset?.let { """, "screenOffset": $it""" } ?: ""}}
          ]}
        ]}
    """

    @Test
    fun newHallsGetTheSeedScreenOffsetOrStartCentred() = runTest {
        seeder(seedWithOffset(1, -1.5f)).seedIfNeeded()
        assertEquals(-1.5f, halls.getHall(db.hallDao().getAll().single().id)!!.screenOffset)

        seeder(v1.replace("\"seedVersion\": 1", "\"seedVersion\": 2")).seedIfNeeded()
        assertEquals(-1.5f, halls.getHall(db.hallDao().getAll().single().id)!!.screenOffset) // never reset
    }

    @Test
    fun screenOffsetFollowsTheFillRulesWithZeroAsEmpty() = runTest {
        seeder(seedWithOffset(1, null)).seedIfNeeded()
        val hallId = db.hallDao().getAll().single().id
        assertEquals(0f, halls.getHall(hallId)!!.screenOffset)

        // An explicit 0 in the seed is the same as none.
        seeder(seedWithOffset(2, 0f)).seedIfNeeded()
        assertEquals(0f, halls.getHall(hallId)!!.screenOffset)

        // A still centred screen gets a new seed offset.
        seeder(seedWithOffset(3, 2f)).seedIfNeeded()
        assertEquals(2f, halls.getHall(hallId)!!.screenOffset)

        // A user's offset is never overwritten.
        halls.updateHall(halls.getHall(hallId)!!.copy(screenOffset = -1f))
        seeder(seedWithOffset(4, 3f)).seedIfNeeded()
        assertEquals(-1f, halls.getHall(hallId)!!.screenOffset)

        // Centred by the user: the same seed offset doesn't come back...
        halls.updateHall(halls.getHall(hallId)!!.copy(screenOffset = 0f))
        seeder(seedWithOffset(5, 3f)).seedIfNeeded()
        assertEquals(0f, halls.getHall(hallId)!!.screenOffset)
        // ...but a changed one fills it, like a cleared address gets a changed seed address.
        seeder(seedWithOffset(6, 2.5f)).seedIfNeeded()
        assertEquals(2.5f, halls.getHall(hallId)!!.screenOffset)
    }

    @Test
    fun seedingKeepsFavouriteCinemas() = runTest {
        seeder(v1).seedIfNeeded()
        val alpha = db.cinemaDao().getAll().single { it.seedKey == "alpha" }
        val mine = cinemas.createCinema("Gamma") // adopted by the v2 seed
        cinemas.setFavourite(alpha.id, true)
        cinemas.setFavourite(mine, true)

        seeder(v2).seedIfNeeded()

        val all = db.cinemaDao().getAll()
        assertEquals(setOf(alpha.id, mine), all.filter { it.isFavourite }.map { it.id }.toSet())
        assertEquals("gamma", all.single { it.id == mine }.seedKey)
    }

    @Test
    fun seedLayoutFilledIntoAHallRespectsItsSeatIdMark() = runTest {
        seeder(seedWith(1, null, "SSS")).seedIfNeeded() // ids 1..3
        val hall = halls.getHall(db.hallDao().getAll().single().id)!!
        assertEquals(4, hall.nextSeatId)
        visits.save(Visit(hallId = hall.id, date = LocalDate.of(2026, 1, 1), seatId = SeatId(2)))
        halls.updateHall(hall.copy(layout = null)) // user clears the layout

        seeder(seedWith(2, null, "SS|SS")).seedIfNeeded() // a newly transcribed layout

        val refilled = halls.getHall(hall.id)!!.layout!!
        assertTrue(refilled.seatIds.all { it.value >= 4 })
        // The visit on old seat 2 does not attach to a seat of the new layout.
        assertTrue(halls.observeHallDetail(hall.id).first()!!.seats.values.all { it.visitCount == 0 })
        assertEquals(8, halls.getHall(hall.id)!!.nextSeatId)
    }

    @Test
    fun userAddedItemsWithTheSameNameAreAdoptedInsteadOfDuplicated() = runTest {
        val cinemaId = cinemas.createCinema("  alpha ", address = null, notes = "mine")
        val hallId = halls.createHall(cinemaId, "SAAL 1")
        val otherCinema = cinemas.createCinema("Delta")
        val otherHall = halls.createHall(otherCinema, "Saal 1") // same hall name, other cinema

        seeder(seedWith(1, "Street 1", "SS")).seedIfNeeded()

        val all = db.cinemaDao().getAll()
        assertEquals(2, all.size)
        val alpha = all.single { it.id == cinemaId }
        assertEquals("alpha", alpha.name) // the user's name is kept
        assertEquals("alpha", alpha.seedKey)
        assertFalse(alpha.isPreloaded) // adopted items stay user-deletable
        assertEquals("Street 1", alpha.address) // empty field filled
        assertEquals("mine", alpha.notes)

        val hallsAfter = db.hallDao().getAll()
        assertEquals(2, hallsAfter.size)
        val adopted = hallsAfter.single { it.id == hallId }
        assertEquals("SAAL 1", adopted.name)
        assertEquals("alpha/1", adopted.seedKey)
        assertFalse(adopted.isPreloaded) // adopted items stay user-deletable
        assertEquals(layoutOf("SS"), halls.getHall(hallId)!!.layout)
        assertNull(hallsAfter.single { it.id == otherHall }.seedKey)
    }

    @Test
    fun seededDataIsVisibleThroughRepositories() = runTest {
        seeder(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText()).seedIfNeeded()
        val list = cinemas.observeCinemas().first()
        assertEquals(14, list.size)
        assertEquals("Babylon Kreuzberg", list.first().cinema.name)
        assertTrue(list.all { it.visitCount == 0 && it.overallScore == null })
        val lux = list.single { it.cinema.seedKey == "delphi-lux" }
        val detail = cinemas.observeCinemaDetail(lux.cinema.id).first()!!
        assertEquals(listOf("Luxbox") + (1..6).map { "Saal $it" }, detail.halls.map { it.hall.name })
    }
}
