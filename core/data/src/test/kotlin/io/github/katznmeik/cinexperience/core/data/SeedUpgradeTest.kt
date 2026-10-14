package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Upgrading an install that was seeded with seed version 1 (halls without
 * layouts; the file is kept as a test fixture) to the shipped seed, which adds
 * the delphi LUX layouts.
 */
internal class SeedUpgradeTest : DatabaseTest() {

    private val v1 = File("src/test/resources/seed/yorck-v1.json").readText()
    private val current = File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText()

    /** The shipped seed's layouts by hall seed key, with ids from 1. */
    private val seedLayouts: Map<String, SeatLayout> = SeedParser.parse(current).cinemas
        .flatMap { it.halls }
        .mapNotNull { hall -> hall.layout?.let { hall.seedKey to LayoutJson.fromJson(it) } }
        .toMap()

    private suspend fun luxHalls(): Map<String, Hall> =
        db.hallDao().getAll().filter { it.seedKey?.startsWith("delphi-lux/") == true }
            .associate { it.seedKey!! to halls.getHall(it.id)!! }

    @Test
    fun version1InstallGetsTheDelphiLuxLayouts() = runTest {
        assertTrue(Seeder(db) { v1 }.seedIfNeeded())
        val before = luxHalls()
        assertEquals((1..7).map { "Saal $it" }, before.values.sortedBy { it.seedKey }.map { it.name })
        assertTrue(before.values.all { it.layout == null && it.screenWidth == null })
        val hallCount = db.hallDao().getAll().size

        // The user renamed one hall, drew and kept a layout in another, and drew,
        // used and cleared a layout in a third.
        val saal3 = before.getValue("delphi-lux/saal-3")
        halls.renameHall(saal3.id, "Kleiner Saal")
        val saal4 = before.getValue("delphi-lux/saal-4")
        val drawn = layoutOf("SSSS", "SSSS")
        halls.updateHall(saal4.copy(layout = drawn))
        val saal2 = before.getValue("delphi-lux/saal-2")
        halls.updateHall(saal2.copy(layout = layoutOf("SSS")))
        visits.save(Visit(hallId = saal2.id, date = LocalDate.of(2026, 1, 1), seatId = SeatId(2)))
        halls.updateHall(halls.getHall(saal2.id)!!.copy(layout = null))
        assertEquals(4, halls.getHall(saal2.id)!!.nextSeatId)

        assertTrue(Seeder(db) { current }.seedIfNeeded())
        assertFalse(Seeder(db) { current }.seedIfNeeded())

        val after = luxHalls()
        assertEquals(hallCount, db.hallDao().getAll().size) // nothing inserted
        assertEquals(before.mapValues { it.value.id }, after.mapValues { it.value.id })
        // Names are never touched: the user's rename stays, and "Saal 7" is not renamed to "Luxbox".
        assertEquals(
            listOf("Saal 1", "Saal 2", "Kleiner Saal", "Saal 4", "Saal 5", "Saal 6", "Saal 7"),
            after.values.sortedBy { it.seedKey }.map { it.name },
        )

        // Halls without a layout get the seed layout with ids from 1.
        for (key in listOf("saal-1", "saal-3", "saal-5", "saal-6", "saal-7").map { "delphi-lux/$it" }) {
            val hall = after.getValue(key)
            val seed = seedLayouts.getValue(key)
            assertEquals(key, seed, hall.layout)
            assertEquals(key, seed.nextSeatId, hall.nextSeatId)
        }
        val saal1 = after.getValue("delphi-lux/saal-1")
        assertEquals(14f, saal1.screenWidth)
        assertEquals(1.5f, saal1.screenDistance)
        assertEquals(119, saal1.layout!!.seatCount)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", null, "8", "9", "10"), saal1.layout!!.labels().rowLabels)
        assertEquals(35, after.getValue("delphi-lux/saal-7").layout!!.seatCount)

        // The hall whose old seats 1..3 were used gets the seed layout above that mark.
        val saal2After = after.getValue("delphi-lux/saal-2")
        val seed2 = seedLayouts.getValue("delphi-lux/saal-2")
        assertEquals(seed2.withIdsShiftedBy(3), saal2After.layout)
        assertEquals((4..140).map(::SeatId).toSet(), saal2After.layout!!.seatIds)
        assertEquals(141, saal2After.nextSeatId)
        assertEquals(seed2.labels().seats.values.map { it.label }, saal2After.layout!!.labels().seats.values.map { it.label })

        // A drawn layout is kept; empty screen values are still filled.
        val saal4After = after.getValue("delphi-lux/saal-4")
        assertEquals(drawn, saal4After.layout)
        assertEquals(10f, saal4After.screenWidth)
        assertEquals(2f, saal4After.screenDistance)
    }

    @Test
    fun freshInstallNamesTheSeventhHallLuxbox() = runTest {
        assertTrue(Seeder(db) { current }.seedIfNeeded())
        val lux = luxHalls()
        assertEquals("Luxbox", lux.getValue("delphi-lux/saal-7").name)
        assertEquals(seedLayouts.keys, lux.filterValues { it.layout != null }.keys)
        lux.forEach { (key, hall) ->
            assertEquals(key, seedLayouts.getValue(key), hall.layout)
            assertEquals(seedLayouts.getValue(key).nextSeatId, hall.nextSeatId)
            assertTrue(hall.isPreloaded)
        }
        // Halls of other cinemas still have no layout.
        assertTrue(db.hallDao().getAll().filter { it.seedKey?.startsWith("delphi-lux/") == false }.all { it.layoutJson == null })
        assertNull(db.hallDao().getAll().firstOrNull { it.seedKey == null })
    }
}
