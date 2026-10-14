package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.db.MetadataEntity
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.SeedJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Seeding the shipped seed (version 5: all 38 halls with layouts from yorck.de,
 * Blauer Stern added, Kant Kino Saal 3 as double sofas) on a fresh install and on
 * installs seeded with older seed versions: version 1 (halls without layouts) and
 * version 2 (the delphi LUX layouts transcribed from Yorck-app screenshots), kept
 * as test fixtures, and version 4 (Kant Kino Saal 3 as normal seats), derived from
 * the shipped seed.
 */
internal class SeedUpgradeTest : DatabaseTest() {

    private val v1 = File("src/test/resources/seed/yorck-v1.json").readText()
    private val v2 = File("src/test/resources/seed/yorck-v2.json").readText()
    private val current = File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText()

    /**
     * Version 4: the shipped seed with Kant Kino Saal 3's 22 places as normal seats, as
     * yorck.de lists them. That was its only difference: version 5 changed nothing else.
     * Saal 3's rows are the seed's only rows labelled "-" (rows without a name).
     */
    private val v4 = current
        .replace("\"seedVersion\": 5,", "\"seedVersion\": 4,")
        .lines()
        .joinToString("\n") { line -> if ("\"label\": \"-\"" in line) line.replace("FF", "SS") else line }
    private val currentSeed: SeedJson = SeedParser.parse(current)

    /** The shipped seed's layouts by hall seed key, with ids from 1. */
    private val seedLayouts: Map<String, SeatLayout> = currentSeed.cinemas
        .flatMap { it.halls }
        .mapNotNull { hall -> hall.layout?.let { hall.seedKey to LayoutJson.fromJson(it) } }
        .toMap()

    /** Version 2's layouts (delphi LUX only), with ids from 1. */
    private val v2Layouts: Map<String, SeatLayout> = SeedParser.parse(v2).cinemas
        .flatMap { it.halls }
        .mapNotNull { hall -> hall.layout?.let { hall.seedKey to LayoutJson.fromJson(it) } }
        .toMap()

    private val luxKeys = (1..7).map { "delphi-lux/saal-$it" }

    private suspend fun allHalls(): Map<String, Hall> =
        db.hallDao().getAll().associate { it.seedKey!! to halls.getHall(it.id)!! }

    private suspend fun luxHalls(): Map<String, Hall> = allHalls().filterKeys { it.startsWith("delphi-lux/") }

    private suspend fun seed(json: String): Boolean = Seeder(db) { json }.seedIfNeeded()

    @Test
    fun freshInstallGetsEveryHallsLayoutAndBlauerStern() = runTest {
        assertTrue(seed(current))
        val cinemaRows = db.cinemaDao().getAll()
        assertEquals(14, cinemaRows.size)
        val blauerStern = cinemaRows.single { it.seedKey == "blauer-stern" }
        assertEquals("Blauer Stern", blauerStern.name)
        assertEquals("Hermann-Hesse-Straße 11, 13156 Berlin", blauerStern.address)
        assertTrue(blauerStern.isPreloaded)

        val all = allHalls()
        assertEquals(38, all.size)
        assertEquals(seedLayouts.keys, all.keys)
        assertEquals(listOf("Saal 1", "Saal 2"), listOf("blauer-stern/saal-1", "blauer-stern/saal-2").map { all.getValue(it).name })
        assertEquals("Luxbox", all.getValue("delphi-lux/saal-7").name)
        val seedHalls = currentSeed.cinemas.flatMap { it.halls }.associateBy { it.seedKey }
        all.forEach { (key, hall) ->
            assertEquals(key, seedLayouts.getValue(key), hall.layout)
            assertEquals(key, seedLayouts.getValue(key).nextSeatId, hall.nextSeatId)
            assertEquals(key, seedHalls.getValue(key).screenWidth, hall.screenWidth)
            assertEquals(key, seedHalls.getValue(key).screenDistance, hall.screenDistance)
            assertEquals(key, 0f, hall.screenOffset)
            assertTrue(hall.isPreloaded)
        }
        // A new hall's screen spans its columns, 1.5 rows in front of row 1.
        val international = all.getValue("international/saal-1")
        assertEquals(34f, international.screenWidth)
        assertEquals(1.5f, international.screenDistance)
    }

    @Test
    fun version1InstallGetsEveryLayout() = runTest {
        assertTrue(seed(v1))
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

        assertTrue(seed(current))
        assertFalse(seed(current))

        val after = luxHalls()
        assertEquals(hallCount + 2, db.hallDao().getAll().size) // only Blauer Stern's two halls are new
        assertEquals(before.mapValues { it.value.id }, after.mapValues { it.value.id })
        // Names are never touched: the user's rename stays, and "Saal 7" is not renamed to "Luxbox".
        assertEquals(
            listOf("Saal 1", "Saal 2", "Kleiner Saal", "Saal 4", "Saal 5", "Saal 6", "Saal 7"),
            after.values.sortedBy { it.seedKey }.map { it.name },
        )

        // Halls without a layout get the seed layout with ids from 1, in every cinema.
        allHalls().filterKeys { it != "delphi-lux/saal-2" && it != "delphi-lux/saal-4" }.forEach { (key, hall) ->
            assertEquals(key, seedLayouts.getValue(key), hall.layout)
            assertEquals(key, seedLayouts.getValue(key).nextSeatId, hall.nextSeatId)
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
    fun version2InstallGetsTheNewDelphiLuxLayoutsAndEveryNewHall() = runTest {
        assertTrue(seed(v2))
        val before = luxHalls()
        before.forEach { (key, hall) -> assertEquals(key, v2Layouts.getValue(key), hall.layout) }
        val idsBefore = db.hallDao().getAll().associate { it.seedKey to it.id }
        assertEquals(36, idsBefore.size)

        assertTrue(seed(current))

        val after = allHalls()
        assertEquals(38, after.size)
        // Existing halls keep their ids; only Blauer Stern's halls are new.
        assertEquals(idsBefore, after.mapValues { it.value.id }.filterKeys { it in idsBefore })
        assertEquals(setOf("blauer-stern/saal-1", "blauer-stern/saal-2"), after.keys - idsBefore.keys)
        // The yorck.de layouts differ from the screenshot ones in three halls: Saal 2 and
        // Saal 5 are numbered right to left, and the Luxbox has a second wheelchair space.
        val changed = luxKeys.filterNot { seedLayouts.getValue(it).hasSameStructureAs(v2Layouts.getValue(it)) }
        assertEquals(listOf("delphi-lux/saal-2", "delphi-lux/saal-5", "delphi-lux/saal-7"), changed)
        after.forEach { (key, hall) ->
            val seed = seedLayouts.getValue(key)
            if (key in changed) {
                // The untouched, unused screenshot layouts are replaced (spec §9), with new
                // ids above the old seed ids, which are never reused.
                val old = v2Layouts.getValue(key)
                assertEquals(key, seed.withIdsShiftedBy(old.nextSeatId - 1), hall.layout)
                assertEquals(key, old.nextSeatId - 1 + seed.nextSeatId, hall.nextSeatId)
            } else {
                // Unchanged layouts (and the new halls' first layouts) have the seed's ids.
                assertEquals(key, seed, hall.layout)
            }
            if (key in luxKeys) {
                // delphi LUX keeps its screens measured from the Yorck app.
                assertEquals(key, before.getValue(key).screenWidth, hall.screenWidth)
                assertEquals(key, before.getValue(key).screenDistance, hall.screenDistance)
            } else {
                // The other halls get the seed's screen with their first layout.
                assertEquals(key, seed.columnCount.toFloat(), hall.screenWidth)
                assertEquals(key, 1.5f, hall.screenDistance)
            }
            assertEquals(key, seed.labels().seats.values.map { it.label }, hall.layout!!.labels().seats.values.map { it.label })
        }
        // Now numbered as at Yorck, e.g. Saal 2 row 1 from right to left.
        val saal2 = after.getValue("delphi-lux/saal-2").layout!!
        assertEquals(listOf("11", "10", "9"), saal2.rows[0].seatIds.take(3).map { saal2.labels().labelOf(it)!!.seat })
    }

    @Test
    fun aDelphiLuxHallWithAVisitOrAnOverrideKeepsItsOldLayout() = runTest {
        assertTrue(seed(v2))
        val before = luxHalls()
        val withVisit = before.getValue("delphi-lux/saal-2")
        visits.save(Visit(hallId = withVisit.id, date = LocalDate.of(2026, 9, 1), seatId = withVisit.layout!!.rows[2].seatIds[4]))
        val withColour = before.getValue("delphi-lux/saal-5")
        overrides.setSeatColour(withColour.id, withColour.layout!!.rows[1].seatIds[0], SeatColour.RED)
        val withFavourite = before.getValue("delphi-lux/saal-7")
        overrides.setFavouriteSeat(withFavourite.id, withFavourite.layout!!.rows[4].seatIds[4])

        assertTrue(seed(current))

        val after = luxHalls()
        for (key in listOf("delphi-lux/saal-2", "delphi-lux/saal-5", "delphi-lux/saal-7")) {
            assertEquals(key, v2Layouts.getValue(key), after.getValue(key).layout)
            assertEquals(key, before.getValue(key).nextSeatId, after.getValue(key).nextSeatId)
            assertFalse(key, after.getValue(key).layout!!.hasSameStructureAs(seedLayouts.getValue(key)))
        }
    }

    @Test
    fun aDelphiLuxHallWithAVisitWithoutASeatKeepsItsOldLayout() = runTest {
        assertTrue(seed(v2))
        val saal2 = luxHalls().getValue("delphi-lux/saal-2")
        visits.save(Visit(hallId = saal2.id, date = LocalDate.of(2026, 9, 2)))
        val unused = luxHalls().getValue("delphi-lux/saal-5")

        assertTrue(seed(current))

        assertEquals(v2Layouts.getValue("delphi-lux/saal-2"), halls.getHall(saal2.id)!!.layout)
        // The unused hall next to it is updated.
        assertTrue(halls.getHall(unused.id)!!.layout!!.hasSameStructureAs(seedLayouts.getValue("delphi-lux/saal-5")))
    }

    @Test
    fun version4InstallGetsKantKinoSaal3AsDoubleSofas() = runTest {
        assertTrue(seed(v4))
        val before = allHalls()
        val saal3 = before.getValue("kant-kino/saal-3")
        val sofas = seedLayouts.getValue("kant-kino/saal-3")
        // Version 4: the same seats, numbers and unnamed rows, as normal seats.
        assertTrue(saal3.layout!!.hasSameStructureAs(sofas.withKind(SeatKind.STANDARD)))
        assertEquals(23, saal3.nextSeatId)

        assertTrue(seed(current))

        val after = allHalls()
        assertEquals(before.mapValues { it.value.id }, after.mapValues { it.value.id })
        // The untouched, unused hall gets the sofas (spec §9), with ids above the old ones.
        val saal3After = after.getValue("kant-kino/saal-3")
        assertEquals(sofas.withIdsShiftedBy(22), saal3After.layout)
        assertEquals(45, saal3After.nextSeatId)
        assertEquals(sofas.labels().seats.values.map { it.label }, saal3After.layout!!.labels().seats.values.map { it.label })
        assertTrue(saal3After.layout!!.labels().seats.values.all { it.label.row == null && it.label.kind == SeatKind.SOFA })
        assertEquals(5f, saal3After.screenWidth)
        assertEquals(1.5f, saal3After.screenDistance)
        assertEquals(saal3.name, saal3After.name)
        // Nothing else changes.
        (after - "kant-kino/saal-3").forEach { (key, hall) -> assertEquals(key, before.getValue(key), hall) }
        assertFalse(seed(current))
    }

    @Test
    fun aUsedKantKinoSaal3KeepsItsSeats() = runTest {
        assertTrue(seed(v4))
        val saal3 = allHalls().getValue("kant-kino/saal-3")
        visits.save(Visit(hallId = saal3.id, date = LocalDate.of(2026, 10, 2), seatId = saal3.layout!!.rows[0].seatIds[1]))

        assertTrue(seed(current))

        assertEquals(saal3.layout, halls.getHall(saal3.id)!!.layout)
        assertEquals(23, halls.getHall(saal3.id)!!.nextSeatId)
    }

    /** This layout with every seat of [kind]. */
    private fun SeatLayout.withKind(kind: SeatKind): SeatLayout =
        rows.indices.fold(this) { acc, row ->
            acc.rows[row].cells.indices.fold(acc) { inner, column ->
                if (inner.rows[row].cells[column] is LayoutCell.Seat) inner.withSeatKind(row, column, kind) else inner
            }
        }

    @Test
    fun seedingTwiceChangesNothing() = runTest {
        assertTrue(seed(v2))
        assertTrue(seed(current))
        val halls1: List<HallEntity> = db.hallDao().getAll()
        val cinemas1 = db.cinemaDao().getAll()
        val snapshots1 = db.seedSnapshotDao().getAll()

        assertFalse(seed(current))
        // Even when the same seed is applied again (as if the version had not been recorded).
        db.metadataDao().put(MetadataEntity(Seeder.KEY_SEED_VERSION, "2"))
        assertTrue(seed(current))

        assertEquals(halls1, db.hallDao().getAll())
        assertEquals(cinemas1, db.cinemaDao().getAll())
        assertEquals(snapshots1.toSet(), db.seedSnapshotDao().getAll().toSet())
    }
}
