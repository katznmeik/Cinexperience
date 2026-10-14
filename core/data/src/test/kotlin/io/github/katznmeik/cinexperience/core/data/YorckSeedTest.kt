package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.SeedHallJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped seed's layouts against Yorck's own seat plans (spec §4.3, §9).
 *
 * The fixture `src/test/resources/seed/yorck-seat-numbers.json` was generated
 * from the yorck.de seat plans alone (`scripts/seed/yorck_seatplan_to_layout.py`):
 * per hall, Yorck's rows nearest the screen first, each with its row label and
 * its places left to right as drawn, with seat text and kind. Every seat of every
 * hall must get exactly that label from [labels].
 *
 * One documented exception ([kindCorrections], spec §9): Kant Kino Saal 3 is 11
 * double sofas, which Yorck's data lists as normal seats; the seed has them as sofa
 * places (the generator's `KIND_OVERRIDES`). The fixture keeps Yorck's kinds; the
 * numbers and row labels are still checked exactly.
 */
class YorckSeedTest {

    @Serializable
    private data class YorckRow(val row: String, val seats: List<String>, val kinds: String)

    @Serializable
    private data class YorckSeats(val source: String, val halls: Map<String, List<YorckRow>>)

    private val seedHalls: Map<String, SeedHallJson> =
        SeedParser.parse(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText())
            .cinemas.flatMap { it.halls }.associateBy { it.seedKey }

    private val yorck: Map<String, List<YorckRow>> = Json.decodeFromString(
        YorckSeats.serializer(),
        File("src/test/resources/seed/yorck-seat-numbers.json").readText(),
    ).halls

    private fun layout(key: String): SeatLayout = LayoutJson.fromJson(seedHalls.getValue(key).layout!!)

    private val kindCodes = mapOf(SeatKind.STANDARD to 'S', SeatKind.WHEELCHAIR to 'W', SeatKind.COMPANION to 'C', SeatKind.SOFA to 'F')

    /**
     * Halls whose seed kinds deliberately differ from Yorck's (spec §9): every place
     * Yorck lists as a normal seat ('S') is this kind in the seed. Nothing else may differ.
     */
    private val kindCorrections = mapOf("kant-kino/saal-3" to 'F')

    /** Yorck's fixture rows of [key] with the [kindCorrections] applied: what the seed must hold. */
    private fun expectedRows(key: String): List<YorckRow> {
        val rows = yorck.getValue(key)
        val corrected = kindCorrections[key] ?: return rows
        return rows.map { it.copy(kinds = it.kinds.replace('S', corrected)) }
    }

    /**
     * The layout's seat rows as Yorck's fixture states them: label, seat texts and kinds
     * left to right. A row without a name has Yorck's label "-" (and no label in the app).
     */
    private fun SeatLayout.asYorckRows(): List<YorckRow> {
        val labels = labels()
        return rows.indices.filter { rows[it].seatIds.isNotEmpty() }.map { index ->
            val seats = rows[index].seatIds.map { labels.seats.getValue(it).label }
            val unnamed = rows[index].hasNoName
            assertEquals(unnamed, labels.rowLabels[index] == null)
            assertTrue(seats.all { (it.row == null) == unnamed })
            YorckRow(
                row = if (unnamed) LayoutRow.NO_NAME else labels.rowLabels[index]!!,
                seats = seats.map { it.seat },
                kinds = seats.map { kindCodes.getValue(it.kind) }.joinToString(""),
            )
        }
    }

    /** Seat texts of the row labelled [row] (the first one with that label), left to right. */
    private fun SeatLayout.texts(row: String): List<String> = asYorckRows().first { it.row == row }.seats

    private fun SeatLayout.rowIndex(label: String): Int = labels().rowLabels.indexOf(label)

    private fun SeatLayout.label(row: String, index: Int): SeatLabel =
        labels().labelOf(rows[rowIndex(row)].seatIds[index])!!

    private data class Counts(val places: Int, val rows: Int, val wheelchair: Int, val companion: Int, val sofa: Int)

    /** Yorck's numbers per hall (from the fixture), written out to show what the seed holds. */
    private val yorckCounts = mapOf(
        "babylon-kreuzberg/saal-1" to Counts(places = 171, rows = 11, wheelchair = 2, companion = 1, sofa = 2),
        "babylon-kreuzberg/saal-2" to Counts(places = 51, rows = 6, wheelchair = 0, companion = 0, sofa = 0),
        "blauer-stern/saal-1" to Counts(places = 165, rows = 12, wheelchair = 2, companion = 2, sofa = 0),
        "blauer-stern/saal-2" to Counts(places = 68, rows = 6, wheelchair = 1, companion = 1, sofa = 0),
        "capitol-dahlem/saal-1" to Counts(places = 148, rows = 21, wheelchair = 0, companion = 0, sofa = 0),
        "cinema-paris/saal-1" to Counts(places = 322, rows = 18, wheelchair = 0, companion = 0, sofa = 0),
        "delphi-filmpalast/saal-1" to Counts(places = 673, rows = 36, wheelchair = 0, companion = 0, sofa = 0),
        "delphi-lux/saal-1" to Counts(places = 119, rows = 10, wheelchair = 4, companion = 0, sofa = 0),
        "delphi-lux/saal-2" to Counts(places = 137, rows = 12, wheelchair = 3, companion = 1, sofa = 0),
        "delphi-lux/saal-3" to Counts(places = 81, rows = 9, wheelchair = 3, companion = 0, sofa = 0),
        "delphi-lux/saal-4" to Counts(places = 54, rows = 6, wheelchair = 1, companion = 1, sofa = 0),
        "delphi-lux/saal-5" to Counts(places = 50, rows = 6, wheelchair = 1, companion = 1, sofa = 6),
        "delphi-lux/saal-6" to Counts(places = 95, rows = 8, wheelchair = 4, companion = 0, sofa = 0),
        "delphi-lux/saal-7" to Counts(places = 35, rows = 5, wheelchair = 2, companion = 1, sofa = 0),
        "filmtheater-am-friedrichshain/saal-1" to Counts(places = 323, rows = 14, wheelchair = 0, companion = 0, sofa = 0),
        "filmtheater-am-friedrichshain/saal-2" to Counts(places = 197, rows = 11, wheelchair = 0, companion = 0, sofa = 0),
        "filmtheater-am-friedrichshain/saal-3" to Counts(places = 97, rows = 8, wheelchair = 0, companion = 0, sofa = 0),
        "filmtheater-am-friedrichshain/saal-4" to Counts(places = 113, rows = 10, wheelchair = 0, companion = 0, sofa = 0),
        "filmtheater-am-friedrichshain/saal-5" to Counts(places = 129, rows = 11, wheelchair = 0, companion = 0, sofa = 0),
        "kant-kino/saal-1" to Counts(places = 256, rows = 20, wheelchair = 0, companion = 0, sofa = 16),
        "kant-kino/saal-2" to Counts(places = 81, rows = 8, wheelchair = 0, companion = 0, sofa = 0),
        "kant-kino/saal-3" to Counts(places = 22, rows = 11, wheelchair = 0, companion = 0, sofa = 0),
        "kant-kino/saal-4" to Counts(places = 62, rows = 9, wheelchair = 1, companion = 1, sofa = 0),
        "kant-kino/saal-5" to Counts(places = 60, rows = 8, wheelchair = 1, companion = 1, sofa = 0),
        "international/saal-1" to Counts(places = 512, rows = 19, wheelchair = 4, companion = 2, sofa = 0),
        "neues-off/saal-1" to Counts(places = 187, rows = 13, wheelchair = 0, companion = 0, sofa = 0),
        "odeon/saal-1" to Counts(places = 265, rows = 17, wheelchair = 4, companion = 4, sofa = 0),
        "passage/saal-1" to Counts(places = 224, rows = 11, wheelchair = 0, companion = 0, sofa = 0),
        "passage/saal-2" to Counts(places = 52, rows = 8, wheelchair = 0, companion = 0, sofa = 0),
        "passage/saal-3" to Counts(places = 55, rows = 9, wheelchair = 0, companion = 0, sofa = 0),
        "passage/saal-4" to Counts(places = 37, rows = 7, wheelchair = 0, companion = 0, sofa = 0),
        "rollberg/saal-1" to Counts(places = 141, rows = 11, wheelchair = 0, companion = 0, sofa = 0),
        "rollberg/saal-2" to Counts(places = 113, rows = 9, wheelchair = 0, companion = 0, sofa = 0),
        "rollberg/saal-3" to Counts(places = 89, rows = 7, wheelchair = 0, companion = 0, sofa = 0),
        "rollberg/saal-4" to Counts(places = 67, rows = 6, wheelchair = 0, companion = 0, sofa = 0),
        "rollberg/saal-5" to Counts(places = 44, rows = 5, wheelchair = 0, companion = 0, sofa = 0),
        "yorck/yorck" to Counts(places = 228, rows = 10, wheelchair = 0, companion = 0, sofa = 0),
        "yorck/new-yorck" to Counts(places = 95, rows = 6, wheelchair = 0, companion = 0, sofa = 0),
    )

    @Test
    fun everyHallOfTheSeedIsInYorcksPlans() {
        assertEquals(38, seedHalls.size)
        assertEquals(seedHalls.keys, yorck.keys)
        assertEquals(seedHalls.keys, yorckCounts.keys)
        assertTrue(seedHalls.values.all { it.layout != null })
    }

    @Test
    fun everySeatOfEveryHallIsLabelledAsAtYorck() {
        val differences = mutableListOf<String>()
        for (key in yorck.keys) {
            val expected = expectedRows(key)
            val actual = layout(key).asYorckRows()
            if (actual.size != expected.size) differences += "$key: ${actual.size} seat rows, Yorck has ${expected.size}"
            expected.zip(actual).forEachIndexed { index, (yorckRow, seedRow) ->
                if (yorckRow != seedRow) differences += "$key seat row ${index + 1}: Yorck $yorckRow, seed $seedRow"
            }
        }
        assertEquals(emptyList<String>(), differences)
        assertEquals(5618, yorck.values.sumOf { rows -> rows.sumOf { it.seats.size } })
    }

    @Test
    fun seatCountsPerKindMatchYorck() {
        for ((key, counts) in yorckCounts) {
            val rows = yorck.getValue(key)
            val kinds = rows.joinToString("") { it.kinds }
            assertEquals(key, counts, Counts(kinds.length, rows.size, kinds.count { it == 'W' }, kinds.count { it == 'C' }, kinds.count { it == 'F' }))
            val layout = layout(key)
            val seats = layout.rows.flatMap { it.cells }.filterIsInstance<LayoutCell.Seat>()
            val seed = Counts(
                places = layout.seatCount,
                rows = layout.rows.count { it.seatIds.isNotEmpty() },
                wheelchair = seats.count { it.kind == SeatKind.WHEELCHAIR },
                companion = seats.count { it.kind == SeatKind.COMPANION },
                sofa = seats.count { it.kind == SeatKind.SOFA },
            )
            val expectedKinds = expectedRows(key).joinToString("") { it.kinds }
            val expected = counts.copy(
                wheelchair = expectedKinds.count { it == 'W' },
                companion = expectedKinds.count { it == 'C' },
                sofa = expectedKinds.count { it == 'F' },
            )
            assertEquals(key, expected, seed)
        }
    }

    @Test
    fun layoutsAreWellFormed() {
        for (key in seedHalls.keys) {
            val dto = seedHalls.getValue(key).layout!!
            val layout = layout(key)
            // Every seat row carries Yorck's label; spacer rows are empty gap rows without one.
            layout.rows.forEach { row ->
                if (row.seatIds.isEmpty()) {
                    assertNull(key, row.label)
                    assertTrue(key, row.cells.all { it.type == CellType.GAP })
                } else {
                    assertTrue(key, row.label != null)
                }
            }
            // Seed ids run row-major from 1.
            assertEquals(key, (1..layout.seatCount).toList(), layout.rows.flatMap { row -> row.seatIds.map { it.value } })
            assertEquals(key, layout.seatCount + 1, layout.nextSeatId)
            // Each layout says the lowest format version that holds it.
            assertEquals(key, LayoutJson.minimumVersion(layout), dto.formatVersion)
            // No column or row of the grid is wasted at the edges.
            assertTrue(key, layout.rows.first().seatIds.isNotEmpty() && layout.rows.last().seatIds.isNotEmpty())
            assertTrue(key, layout.rows.any { it.cells.first() is LayoutCell.Seat })
            assertTrue(key, layout.rows.any { it.cells.last() is LayoutCell.Seat })
        }
    }

    @Test
    fun screens() {
        // delphi LUX keeps the screens measured from the Yorck app.
        val lux = listOf(14f to 1.5f, 14f to 1.5f, 11.5f to 1.5f, 10f to 2f, 9f to 2f, 14f to 1.5f, 9f to 2f)
        lux.forEachIndexed { index, (width, distance) ->
            val hall = seedHalls.getValue("delphi-lux/saal-${index + 1}")
            assertEquals(hall.seedKey, width, hall.screenWidth)
            assertEquals(hall.seedKey, distance, hall.screenDistance)
        }
        // yorck.de draws every screen as wide as the seating: the other halls start with
        // a screen across all columns, 1.5 rows in front of row 1, centred.
        seedHalls.values.filterNot { it.seedKey.startsWith("delphi-lux/") }.forEach { hall ->
            assertEquals(hall.seedKey, layout(hall.seedKey).columnCount.toFloat(), hall.screenWidth)
            assertEquals(hall.seedKey, 1.5f, hall.screenDistance)
            assertNull(hall.seedKey, hall.screenOffset)
        }
    }

    @Test
    fun kantKinoSaal4Row8EndsWithAWheelchairSpaceLabelledR() {
        val layout = layout("kant-kino/saal-4")
        val row = layout.rows[layout.rowIndex("8")]
        assertEquals(NumberingDirection.RIGHT_TO_LEFT, row.numberingDirection)
        assertEquals(listOf("6", "5", "4", "3", "2", "1", "R"), layout.texts("8"))
        assertEquals(SeatLabel("8", "R", SeatKind.WHEELCHAIR), layout.label("8", 6))
        assertEquals(SeatLabel("8", 1, SeatKind.COMPANION), layout.label("8", 5))
        assertEquals("R", (row.cells.last() as LayoutCell.Seat).fixedLabel)
    }

    @Test
    fun kantKinoSaal5Row1StartsWithAWheelchairSpaceLabelledR() {
        val layout = layout("kant-kino/saal-5")
        assertEquals(NumberingDirection.LEFT_TO_RIGHT, layout.rows[0].numberingDirection)
        assertEquals(listOf("R", "1", "2", "3", "4", "5", "6"), layout.texts("1"))
        assertEquals(SeatLabel("1", "R", SeatKind.WHEELCHAIR), layout.label("1", 0))
        assertEquals(SeatLabel("1", 1, SeatKind.COMPANION), layout.label("1", 1))
    }

    @Test
    fun cinemaParisBalconyRow7JumpsFrom8To12() {
        val layout = layout("cinema-paris/saal-1")
        val row = layout.rows[layout.rowIndex("R7")]
        assertEquals(listOf((1..8).map { "$it" }, (12..19).map { "$it" }).flatten(), layout.texts("R7"))
        // The three missing seats are an aisle, so the right block can start at 12.
        assertEquals(listOf(CellType.AISLE, CellType.AISLE, CellType.AISLE), row.cells.subList(8, 11).map { it.type })
        assertEquals(listOf(null, 12), row.blockStartNumbers)
        assertEquals(NumberingDirection.LEFT_TO_RIGHT, row.numberingDirection)
    }

    @Test
    fun delphiLuxSaal1Row1NumbersItsWheelchairSpaces() {
        val layout = layout("delphi-lux/saal-1")
        val row1 = layout.asYorckRows().first()
        assertEquals((1..13).map { "$it" }, row1.seats)
        assertEquals("WWSSSSSSSSSWW", row1.kinds)
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), layout.label("1", 0))
        assertEquals(SeatLabel("1", 2, SeatKind.WHEELCHAIR), layout.label("1", 1))
        assertEquals(SeatLabel("1", 12, SeatKind.WHEELCHAIR), layout.label("1", 11))
        assertEquals(SeatLabel("1", 13, SeatKind.WHEELCHAIR), layout.label("1", 12))
        // Three blocks, numbered continuously; a spacer row before row 8.
        assertEquals(3, layout.labels().seats.values.filter { it.position.row == 1 }.map { it.block }.distinct().size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", null, "8", "9", "10"), layout.labels().rowLabels)
    }

    @Test
    fun delphiLuxNumberingAsAtYorck() {
        // Saal 2, Saal 5 and the Luxbox are numbered right to left, the others left to right.
        val rightToLeft = (1..7).map { "delphi-lux/saal-$it" }.filter { key ->
            layout(key).rows.filter { it.seatIds.isNotEmpty() }.all { it.numberingDirection == NumberingDirection.RIGHT_TO_LEFT }
        }
        assertEquals(listOf("delphi-lux/saal-2", "delphi-lux/saal-5", "delphi-lux/saal-7"), rightToLeft)
        assertEquals((11 downTo 1).map { "$it" }, layout("delphi-lux/saal-2").texts("1"))
        // The Luxbox's first row has two wheelchair spaces next to the companion seat.
        val luxbox = layout("delphi-lux/saal-7")
        assertEquals("SSSSCWW", luxbox.asYorckRows().first().kinds)
        assertEquals((7 downTo 1).map { "$it" }, luxbox.texts("1"))
    }

    @Test
    fun kantKinoSaal3NumbersItsElevenPairsAcrossTheHall() {
        val layout = layout("kant-kino/saal-3")
        val rows = layout.asYorckRows()
        assertEquals(List(11) { "-" }, rows.map { it.row })
        assertEquals(listOf("11", "12"), rows.first().seats)
        assertEquals(listOf("14", "13"), rows[1].seats)
        assertEquals(listOf("1", "2"), rows.last().seats)
        assertEquals((1..22).map { "$it" }.toSet(), rows.flatMap { it.seats }.toSet())
        // Rows without a name: no row labels, seats shown by seat alone.
        assertTrue(layout.rows.all { it.hasNoName })
        assertEquals(List(11) { null }, layout.labels().rowLabels)
        assertEquals(SeatLabel(null, 12, SeatKind.SOFA), layout.labels().labelOf(layout.rows[0].seatIds[1]))
        assertEquals("Seat 12, sofa", layout.labels().labelOf(layout.rows[0].seatIds[1])!!.description)
    }

    @Test
    fun kantKinoSaal3IsDoubleSofasWhereYorckHasNormalSeats() {
        // Yorck's data: 22 normal seats. The seed: 22 sofa places, the only kind correction.
        assertEquals("S".repeat(22), yorck.getValue("kant-kino/saal-3").joinToString("") { it.kinds })
        assertEquals("F".repeat(22), layout("kant-kino/saal-3").asYorckRows().joinToString("") { it.kinds })
        assertEquals(setOf("kant-kino/saal-3"), kindCorrections.keys)
        // Every other hall has exactly Yorck's kinds.
        for (key in yorck.keys - kindCorrections.keys) {
            assertEquals(key, yorck.getValue(key).map { it.kinds }, layout(key).asYorckRows().map { it.kinds })
        }
    }

    @Test
    fun twoAreaHallsShowTheBalconyBehindTheStallsWithYorcksGap() {
        // The balcony keeps the gap yorck.de draws above it, in spacer rows.
        val gaps = mapOf("capitol-dahlem/saal-1" to 2, "cinema-paris/saal-1" to 1, "delphi-filmpalast/saal-1" to 2)
        for ((key, gap) in gaps) {
            val labels = layout(key).labels().rowLabels
            val first = labels.indexOf("R1")
            assertTrue(key, first > gap)
            assertEquals(key, List(gap) { null }, labels.subList(first - gap, first))
            assertNotNull(key, labels[first - gap - 1])
            // Every row before it belongs to the stalls, every seat row after it to the balcony.
            assertTrue(key, labels.take(first).filterNotNull().none { it.startsWith("R") })
            assertTrue(key, labels.drop(first).filterNotNull().all { it.startsWith("R") })
        }
        // Kant Kino Saal 1 has letter rows: the stalls O (at the screen) to A, four spacer rows, the balcony T to P.
        assertEquals(
            ("ONMLKJIHGFEDCBA".map { "$it" }) + List(4) { null } + "TSRQP".map { "$it" },
            layout("kant-kino/saal-1").labels().rowLabels,
        )
    }
}
