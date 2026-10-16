package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutEditSession
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Seed rows with a block start and a seat added in front of the block (spec
 * §4.3): every seat keeps the number Yorck's booking gives it, the new seat
 * takes the number before the start, and removing it again gives the seeded
 * layout back exactly.
 */
class SeedBlockStartsFrontSeatTest {

    private val layouts: Map<String, SeatLayout> =
        SeedParser.parse(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText())
            .cinemas.flatMap { it.halls }
            .filter { it.layout != null }
            .associate { it.seedKey to LayoutJson.fromJson(it.layout!!) }

    @Test
    fun aSeatInFrontOfEverySeededStartKeepsEveryOtherNumber() {
        assertEquals(38, layouts.size)
        var checked = 0
        var parisR7: String? = null
        layouts.forEach { (key, seeded) ->
            // A gap column at both edges (which changes no start), so every block has a cell in front.
            val layout = seeded.insertColumn(0, CellType.GAP).insertColumn(seeded.columnCount + 1, CellType.GAP)
            assertEquals(key, seeded.labels().seats.mapValues { it.value.label }, layout.labels().seats.mapValues { it.value.label })
            layout.rows.forEachIndexed { index, row ->
                row.blocks.forEachIndexed { block, columns ->
                    val start = row.blockStartNumbers.getOrNull(block) ?: return@forEachIndexed
                    if (columns.none { (row.cells[it] as LayoutCell.Seat).fixedLabel == null }) return@forEachIndexed
                    val rightToLeft = row.numberingDirection == NumberingDirection.RIGHT_TO_LEFT
                    val front = if (rightToLeft) columns.last() + 1 else columns.first()
                    // An aisle in front (Cinema Paris R7): a gap column between it and the block changes no start.
                    val base = when (row.cells[if (rightToLeft) front else front - 1]) {
                        LayoutCell.Gap -> layout
                        else -> layout.insertColumn(front, CellType.GAP)
                    }
                    val frontColumn = if (rightToLeft || base !== layout) front else front - 1
                    assertEquals(LayoutCell.Gap, base.cell(index, frontColumn))
                    assertEquals(layout.rows.map { it.blockStartNumbers }, base.rows.map { it.blockStartNumbers })
                    val where = "$key row $index block $block"

                    val before = base.labels()
                    val added = base.withCellType(index, frontColumn, CellType.SEAT)
                    val after = added.labels()
                    val newSeat = (added.cell(index, frontColumn) as LayoutCell.Seat).id
                    base.seatIds.forEach { id -> assertEquals("$where $id", before.labelOf(id), after.labelOf(id)) }
                    assertEquals(where, "${start - 1}", after.labelOf(newSeat)!!.seat)
                    if (key == "cinema-paris/saal-1" && row.label == "R7") parisR7 = after.labelOf(newSeat)!!.seat
                    // Removing the new seat again gives the row back exactly.
                    assertEquals(where, base.rows, added.withCellType(index, frontColumn, CellType.GAP).rows)
                    // In a session, too, with no change left.
                    val session = LayoutEditSession.start(base, hallNextSeatId = base.nextSeatId)
                        .setCellType(index, frontColumn, CellType.SEAT)
                        .setCellType(index, frontColumn, CellType.GAP)
                    assertFalse(where, session.hasChanges)
                    checked++
                }
            }
        }
        // Every seeded start, including Cinema Paris R7's 12 behind its aisle: the new seat there is 11.
        assertEquals(26, checked)
        assertEquals("11", parisR7)
    }

    @Test
    fun theStartSeatPaintedAwayAndANewSeatPaintedInItsPlaceGiveTheSeededNumbers() {
        var checked = 0
        layouts.forEach { (key, layout) ->
            val before = layout.labels()
            layout.rows.forEachIndexed { index, row ->
                row.blocks.forEachIndexed { block, columns ->
                    if (row.blockStartNumbers.getOrNull(block) == null || columns.size < 2) return@forEachIndexed
                    val inOrder = if (row.numberingDirection == NumberingDirection.RIGHT_TO_LEFT) columns.asReversed() else columns
                    val numbered = inOrder.filter { (row.cells[it] as LayoutCell.Seat).fixedLabel == null }
                    if (numbered.size < 2) return@forEachIndexed
                    val column = numbered.first()
                    // Removed, then a new seat drawn in its place: every number is as seeded.
                    val redrawn = layout.withCellType(index, column, CellType.GAP).withCellType(index, column, CellType.SEAT)
                    val after = redrawn.labels()
                    val where = "$key row $index block $block"
                    layout.seatIds.filter { it in redrawn.seatIds }.forEach { id ->
                        assertEquals("$where $id", before.labelOf(id), after.labelOf(id))
                    }
                    val newSeat = (redrawn.cell(index, column) as LayoutCell.Seat).id
                    val oldSeat = (row.cells[column] as LayoutCell.Seat).id
                    // The new seat is a standard one; its number is the removed seat's.
                    assertEquals(where, before.labelOf(oldSeat)!!.seat, after.labelOf(newSeat)!!.seat)
                    checked++
                }
            }
        }
        assertTrue("$checked", checked >= 25)
    }
}
