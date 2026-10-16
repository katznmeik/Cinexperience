package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seed's block start numbers through the re-keying that every cell change
 * applies (`LayoutRow.withBlockStartsKeyedOn`, spec §4.3): an edit that leaves
 * every block's seats as they are must leave every start, and so every seat
 * number, exactly as seeded.
 */
class SeedBlockStartsTest {

    private val layouts: Map<String, SeatLayout> =
        SeedParser.parse(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText())
            .cinemas.flatMap { it.halls }
            .filter { it.layout != null }
            .associate { it.seedKey to LayoutJson.fromJson(it.layout!!) }

    @Test
    fun reKeyingAnUneditedSeedLayoutChangesNothing() {
        assertEquals(38, layouts.size)
        layouts.forEach { (key, layout) ->
            // A gap column added and removed again: every row's cells pass through the re-keying twice.
            val roundTrip = layout.insertColumn(layout.columnCount, CellType.GAP).removeColumn(layout.columnCount)
            assertEquals(key, layout.rows, roundTrip.rows)
            assertEquals(key, layout.labels(), roundTrip.labels())
            // A gap added inside a row doesn't split it, so its starts stay too.
            val widened = layout.insertColumn(0, CellType.GAP)
            assertEquals(key, layout.rows.map { it.blockStartNumbers }, widened.rows.map { it.blockStartNumbers })
        }
    }

    @Test
    fun theSeedStartsAreNormalisedAndEveryRightToLeftSingleStartKeepsItsNumbersWhenSplit() {
        var rightToLeftSingleStarts = 0
        layouts.forEach { (key, layout) ->
            layout.rows.forEachIndexed { index, row ->
                val starts = row.blockStartNumbers
                assertEquals("$key row $index", starts.take(row.blocks.size).dropLastWhile { it == null }, starts)
                val rightToLeft = row.numberingDirection == NumberingDirection.RIGHT_TO_LEFT
                if (!rightToLeft || starts.size != 1 || starts[0] == null) return@forEachIndexed
                rightToLeftSingleStarts++
                // Kant Kino style (I5): an aisle inserted into the block (no seat removed) must not
                // renumber any seat of the row; the bug gave 6 5 4 3 | 4 3 2 1.
                val block = row.blocks.single()
                if (block.size < 2) return@forEachIndexed
                val split = layout.insertColumn(block[maxOf(1, block.size / 2)], CellType.AISLE)
                assertEquals("$key row $index", 2, split.rows[index].blocks.size)
                val before = layout.labels()
                val after = split.labels()
                row.seatIds.forEach { id ->
                    assertEquals("$key row $index $id", before.labelOf(id), after.labelOf(id))
                }
            }
        }
        assertTrue("$rightToLeftSingleStarts", rightToLeftSingleStarts >= 18)
    }
}
