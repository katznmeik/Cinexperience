package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Seed rows whose block start's seat is removed (spec §4.3): the start moves on
 * to the next seat with its number, so every remaining seat keeps the number
 * Yorck's booking gives it.
 */
class SeedBlockStartsMoveOnTest {

    private val layouts: Map<String, SeatLayout> =
        SeedParser.parse(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText())
            .cinemas.flatMap { it.halls }
            .filter { it.layout != null }
            .associate { it.seedKey to LayoutJson.fromJson(it.layout!!) }

    /** Seat texts of grid row [row], left to right. */
    private fun SeatLayout.texts(row: Int): List<String> {
        val labels = labels()
        return rows[row].seatIds.map { labels.labelOf(it)!!.seat }
    }

    /** Column of the first numbered seat, in numbering order, of the single block of grid row [row] (the start's seat). */
    private fun SeatLayout.startSeatColumn(row: Int): Int {
        val r = rows[row]
        val block = r.blocks.single()
        val inOrder = if (r.numberingDirection == NumberingDirection.RIGHT_TO_LEFT) block.asReversed() else block
        return inOrder.first { (r.cells[it] as LayoutCell.Seat).fixedLabel == null }
    }

    /** The texts of row [row] without the seat at [column]. */
    private fun SeatLayout.textsWithout(row: Int, column: Int): List<String> =
        texts(row).filterIndexed { i, _ -> rows[row].blocks.flatten().sorted()[i] != column }

    @Test
    fun rollbergSaal5Row5KeepsItsNumbersWhenTheLastColumnIsRemoved() {
        val layout = layouts.getValue("rollberg/saal-5")
        assertEquals(listOf("6", "5", "4", "3", "2"), layout.texts(4))
        val narrower = layout.removeColumn(layout.columnCount - 1)
        assertEquals(listOf("6", "5", "4", "3"), narrower.texts(4))
    }

    @Test
    fun paintingAwayOrAisleingTheStartSeatKeepsTheOtherNumbers() {
        val cases = listOf("kant-kino/saal-3" to 1, "kant-kino/saal-3" to 9, "delphi-filmpalast/saal-1" to 5)
        cases.forEach { (key, row) ->
            val layout = layouts.getValue(key)
            val column = layout.startSeatColumn(row)
            val expected = layout.textsWithout(row, column)
            for (type in listOf(CellType.GAP, CellType.AISLE)) {
                assertEquals("$key row $row $type", expected, layout.withCellType(row, column, type).texts(row))
            }
        }
        // Kant Kino Saal 3, row index 1 (FF, 14 13): the other place stays 14.
        val kant = layouts.getValue("kant-kino/saal-3")
        assertEquals(listOf("14"), kant.withCellType(1, kant.startSeatColumn(1), CellType.GAP).texts(1))

        // Cinema Paris row R7 without seat 12 reads 1–8 | 13–19.
        val paris = layouts.getValue("cinema-paris/saal-1")
        val r7 = paris.rows.indexOfFirst { it.label == "R7" }
        val seat12 = paris.rows[r7].blocks[1].first()
        assertEquals((1..8).map { "$it" } + (13..19).map { "$it" }, paris.withCellType(r7, seat12, CellType.GAP).texts(r7))
    }

    @Test
    fun everySeedRowWithAStartKeepsItsOtherNumbersWithoutItsStartSeat() {
        var checked = 0
        layouts.forEach { (key, layout) ->
            layout.rows.forEachIndexed { index, row ->
                row.blocks.forEachIndexed { block, columns ->
                    if (row.blockStartNumbers.getOrNull(block) == null || columns.size < 2) return@forEachIndexed
                    val inOrder = if (row.numberingDirection == NumberingDirection.RIGHT_TO_LEFT) columns.asReversed() else columns
                    val column = inOrder.first { (row.cells[it] as LayoutCell.Seat).fixedLabel == null }
                    val expected = layout.textsWithout(index, column)
                    assertEquals("$key row $index", expected, layout.withCellType(index, column, CellType.GAP).texts(index))
                    checked++
                }
            }
        }
        assertTrue("$checked", checked >= 25)
    }
}
