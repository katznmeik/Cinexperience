package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.SeedHallJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The delphi LUX layouts in the shipped seed, transcribed from the owner's
 * Yorck-app screenshots. Every number here was counted on the screenshots
 * independently of the seed's pattern strings.
 */
class DelphiLuxSeedTest {

    private val halls: Map<String, SeedHallJson> =
        SeedParser.parse(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText())
            .cinemas.single { it.seedKey == "delphi-lux" }
            .halls.associateBy { it.seedKey }

    private fun layout(key: String): SeatLayout = LayoutJson.fromJson(halls.getValue(key).layout!!)

    /** Seat label of the [index]-th place (any kind, left to right) in the row labelled [row]. */
    private fun SeatLayout.label(row: String, index: Int): SeatLabel {
        val labels = labels()
        val r = labels.rowLabels.indexOf(row)
        return labels.labelOf(rows[r].seatIds[index])!!
    }

    private fun SeatLayout.count(kind: SeatKind): Int =
        rows.sumOf { row -> row.cells.count { it is LayoutCell.Seat && it.kind == kind } }

    private fun check(
        key: String,
        name: String,
        columns: Int,
        rowLabels: List<String?>,
        seatsPerRow: List<Int>,
        wheelchairs: Int = 0,
        companions: Int = 0,
        sofas: Int = 0,
        screenWidth: Float,
        screenDistance: Float,
    ): SeatLayout {
        val hall = halls.getValue(key)
        assertEquals(name, hall.name)
        assertEquals(screenWidth, hall.screenWidth)
        assertEquals(screenDistance, hall.screenDistance)
        val layout = layout(key)
        assertEquals(columns, layout.columnCount)
        assertEquals(rowLabels, layout.labels().rowLabels)
        // Every seat row has an explicit label, so the labels never depend on auto-numbering.
        layout.rows.forEach { assertEquals(it.cells.any { c -> c is LayoutCell.Seat }, it.label != null) }
        assertEquals(seatsPerRow, layout.rows.map { it.seatIds.size })
        assertEquals(seatsPerRow.sum(), layout.seatCount)
        assertEquals(wheelchairs, layout.count(SeatKind.WHEELCHAIR))
        assertEquals(companions, layout.count(SeatKind.COMPANION))
        assertEquals(sofas, layout.count(SeatKind.SOFA))
        // Seed ids run row-major from 1.
        assertEquals((1..layout.seatCount).toList(), layout.rows.flatMap { row -> row.seatIds.map { it.value } })
        assertEquals(layout.seatCount + 1, layout.nextSeatId)
        return layout
    }

    private fun numbers(vararg labels: String): List<String> = labels.toList()

    @Test
    fun saal1() {
        val layout = check(
            "delphi-lux/saal-1", "Saal 1", columns = 17,
            rowLabels = numbers("1", "2", "3", "4", "5", "6", "7") + null + numbers("8", "9", "10"),
            seatsPerRow = listOf(13, 13, 13, 13, 13, 13, 11, 0, 9, 9, 12),
            wheelchairs = 4, screenWidth = 14f, screenDistance = 1.5f,
        )
        // Row 1: wheelchair spaces at both ends, not counted in the seat numbers.
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), layout.label("1", 0))
        assertEquals(SeatLabel("1", 2, SeatKind.WHEELCHAIR), layout.label("1", 1))
        assertEquals(SeatLabel("1", 1), layout.label("1", 2))
        assertEquals(SeatLabel("1", 9), layout.label("1", 10))
        assertEquals(SeatLabel("1", 4, SeatKind.WHEELCHAIR), layout.label("1", 12))
        // Three blocks, numbered continuously.
        assertEquals(SeatLabel("2", 13), layout.label("2", 12))
        assertEquals(3, layout.labels().seats.values.filter { it.position.row == 1 }.map { it.block }.distinct().size)
        // Row 7 has no left block; the spacer row (index 7) is empty.
        assertEquals(SeatLabel("7", 11), layout.label("7", 10))
        assertTrue(layout.rows[7].cells.all { it.type == CellType.GAP })
        assertNull(layout.rows[7].label)
        assertEquals(SeatLabel("10", 12), layout.label("10", 11))
        // Row 10 starts two columns further left than rows 8 and 9.
        assertEquals(2, layout.positionOf(layout.rows[10].seatIds.first())!!.column)
        assertEquals(4, layout.positionOf(layout.rows[9].seatIds.first())!!.column)
    }

    @Test
    fun saal2() {
        val layout = check(
            "delphi-lux/saal-2", "Saal 2", columns = 14,
            rowLabels = (1..12).map { "$it" },
            seatsPerRow = listOf(11, 12, 13, 13, 13, 13, 13, 11, 11, 10, 9, 8),
            wheelchairs = 3, companions = 1, screenWidth = 14f, screenDistance = 1.5f,
        )
        assertEquals(SeatLabel("1", 3, SeatKind.WHEELCHAIR), layout.label("1", 2))
        assertEquals(SeatLabel("1", 1, SeatKind.COMPANION), layout.label("1", 3))
        assertEquals(SeatLabel("1", 8), layout.label("1", 10))
        assertEquals(SeatLabel("3", 13), layout.label("3", 12))
        assertEquals(SeatLabel("12", 8), layout.label("12", 7))
        // The right block of row 12 starts at column 9.
        assertEquals(9, layout.positionOf(layout.rows[11].seatIds[3])!!.column)
    }

    @Test
    fun saal3() {
        val layout = check(
            "delphi-lux/saal-3", "Saal 3", columns = 12,
            rowLabels = (1..9).map { "$it" },
            seatsPerRow = listOf(9, 10, 10, 10, 10, 10, 7, 7, 8),
            wheelchairs = 3, screenWidth = 11.5f, screenDistance = 1.5f,
        )
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), layout.label("1", 0))
        assertEquals(SeatLabel("1", 1), layout.label("1", 1))
        assertEquals(SeatLabel("1", 6), layout.label("1", 6))
        assertEquals(SeatLabel("1", 3, SeatKind.WHEELCHAIR), layout.label("1", 8))
        assertEquals(SeatLabel("9", 8), layout.label("9", 7))
    }

    @Test
    fun saal4() {
        val layout = check(
            "delphi-lux/saal-4", "Saal 4", columns = 10,
            rowLabels = (1..6).map { "$it" },
            seatsPerRow = listOf(9, 10, 10, 10, 7, 8),
            wheelchairs = 1, companions = 1, screenWidth = 10f, screenDistance = 2f,
        )
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), layout.label("1", 0))
        assertEquals(SeatLabel("1", 1, SeatKind.COMPANION), layout.label("1", 1))
        assertEquals(SeatLabel("1", 8), layout.label("1", 8))
        assertEquals(SeatLabel("5", 7), layout.label("5", 6))
    }

    @Test
    fun saal5() {
        val layout = check(
            "delphi-lux/saal-5", "Saal 5", columns = 9,
            rowLabels = (1..6).map { "$it" },
            seatsPerRow = listOf(8, 9, 9, 9, 9, 6),
            wheelchairs = 1, companions = 1, sofas = 6, screenWidth = 9f, screenDistance = 2f,
        )
        assertEquals(SeatLabel("1", 7, SeatKind.COMPANION), layout.label("1", 6))
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), layout.label("1", 7))
        assertEquals(SeatLabel("6", 1, SeatKind.SOFA), layout.label("6", 0))
        assertEquals(SeatLabel("6", 6, SeatKind.SOFA), layout.label("6", 5))
        assertEquals(3, layout.positionOf(layout.rows[5].seatIds.first())!!.column)
    }

    @Test
    fun saal6() {
        val layout = check(
            "delphi-lux/saal-6", "Saal 6", columns = 17,
            rowLabels = (1..8).map { "$it" },
            seatsPerRow = listOf(13, 13, 13, 13, 13, 13, 11, 6),
            wheelchairs = 4, screenWidth = 14f, screenDistance = 1.5f,
        )
        assertEquals(SeatLabel("1", 9), layout.label("1", 10))
        assertEquals(SeatLabel("2", 13), layout.label("2", 12))
        assertEquals(SeatLabel("7", 11), layout.label("7", 10))
        assertEquals(SeatLabel("8", 6), layout.label("8", 5))
        assertEquals(1, layout.positionOf(layout.rows[7].seatIds.first())!!.column)
    }

    @Test
    fun luxbox() {
        val layout = check(
            "delphi-lux/saal-7", "Luxbox", columns = 9,
            rowLabels = (1..5).map { "$it" },
            seatsPerRow = listOf(7, 5, 7, 7, 9),
            wheelchairs = 1, companions = 1, screenWidth = 9f, screenDistance = 2f,
        )
        assertEquals(SeatLabel("1", 5, SeatKind.COMPANION), layout.label("1", 4))
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), layout.label("1", 5))
        assertEquals(SeatLabel("1", 6), layout.label("1", 6))
        assertEquals(SeatLabel("3", 7), layout.label("3", 6))
        assertEquals(7, layout.positionOf(layout.rows[2].seatIds[5])!!.column)
    }

    @Test
    fun allSevenHallsHaveALayout() {
        assertEquals((1..7).map { "delphi-lux/saal-$it" }, halls.keys.toList())
        assertEquals(571, halls.keys.sumOf { layout(it).seatCount })
    }
}
