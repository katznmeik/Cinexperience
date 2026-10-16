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
        layouts.forEach { (key, seeded) ->
            // A gap column at both edges (which changes no start), so every block has a cell in front.
            val layout = seeded.insertColumn(0, CellType.GAP).insertColumn(seeded.columnCount + 1, CellType.GAP)
            assertEquals(key, seeded.labels().seats.mapValues { it.value.label }, layout.labels().seats.mapValues { it.value.label })
            val before = layout.labels()
            layout.rows.forEachIndexed { index, row ->
                row.blocks.forEachIndexed { block, columns ->
                    val start = row.blockStartNumbers.getOrNull(block) ?: return@forEachIndexed
                    if (columns.none { (row.cells[it] as LayoutCell.Seat).fixedLabel == null }) return@forEachIndexed
                    val rightToLeft = row.numberingDirection == NumberingDirection.RIGHT_TO_LEFT
                    val front = if (rightToLeft) columns.last() + 1 else columns.first() - 1
                    if (row.cells[front] != LayoutCell.Gap) return@forEachIndexed
                    val where = "$key row $index block $block"

                    val added = layout.withCellType(index, front, CellType.SEAT)
                    val after = added.labels()
                    val newSeat = (added.cell(index, front) as LayoutCell.Seat).id
                    if (start >= 2) {
                        layout.seatIds.forEach { id -> assertEquals("$where $id", before.labelOf(id), after.labelOf(id)) }
                        assertEquals(where, "${start - 1}", after.labelOf(newSeat)!!.seat)
                    } else {
                        assertEquals(where, "$start", after.labelOf(newSeat)!!.seat)
                    }
                    // Removing the new seat again gives the seeded row back exactly.
                    assertEquals(where, layout.rows, added.withCellType(index, front, CellType.GAP).rows)
                    // In a session, too, with no change left.
                    val session = LayoutEditSession.start(layout, hallNextSeatId = layout.nextSeatId)
                        .setCellType(index, front, CellType.SEAT)
                        .setCellType(index, front, CellType.GAP)
                    assertFalse(where, session.hasChanges)
                    checked++
                }
            }
        }
        assertTrue("$checked", checked >= 25)
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
