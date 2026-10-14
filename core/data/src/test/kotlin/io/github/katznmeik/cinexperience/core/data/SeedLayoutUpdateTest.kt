package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The seed update exception (spec §9): a layout that is still exactly the one an
 * older seed supplied is replaced by the newer seed's layout, with its screen
 * width and distance, if the hall has no visits, seat colours or favourite.
 */
internal class SeedLayoutUpdateTest : DatabaseTest() {

    /** One cinema with one hall "alpha/1"; [rows] are the layout's row objects, `null` = no layout. */
    private fun seed(version: Int, rows: String?, width: Float = 10f, distance: Float = 2f) = """
        {"seedVersion": $version, "cinemas": [
          {"seedKey": "alpha", "name": "Alpha", "halls": [
            {"seedKey": "alpha/1", "name": "Saal 1", "screenWidth": $width, "screenDistance": $distance,
             "layout": ${rows?.let { """{"formatVersion": 3, "rows": [$it]}""" } ?: "null"}}
          ]}
        ]}
    """

    /** The previous seed's layout (transcribed from screenshots): 14 seats, ids 1..14. */
    private val old = """{"label": "1", "cells": "W.SSSS.W"}, {"label": "2", "cells": "SSSSSSSS"}"""

    /** The corrected one (from yorck.de): a fixed "R", row 2 right to left from 3. */
    private val corrected = """{"label": "1", "cells": "W.SSSS.W", "seatLabels": ["R", null, null, null, null, null]},
        {"label": "2", "cells": "SSSSSSSS", "direction": "RIGHT_TO_LEFT", "blockStarts": [3]}"""

    private fun layout(rows: String): SeatLayout = LayoutJson.decode("""{"formatVersion": 3, "rows": [$rows]}""")

    private val correctedLayout: SeatLayout = layout(corrected)

    private suspend fun apply(json: String) = Seeder(db) { json }.seedIfNeeded()

    private suspend fun hall(): Hall = halls.getHall(db.hallDao().getAll().single().id)!!

    private fun SeatLayout.texts(row: Int): List<String> {
        val labels = labels()
        return rows[row].seatIds.map { labels.labelOf(it)!!.seat }
    }

    @Test
    fun anUntouchedUnusedLayoutIsReplaced() = runTest {
        apply(seed(1, old))
        val before = hall()
        assertEquals(layout(old), before.layout)
        assertEquals(15, before.nextSeatId)
        halls.renameHall(before.id, "Kleiner Saal")
        halls.updateHall(hall().copy(notes = "my notes"))

        assertTrue(apply(seed(2, corrected, width = 7.5f, distance = 1.5f)))

        val after = hall()
        // New ids above the hall's mark: 15..28.
        assertEquals(correctedLayout.withIdsShiftedBy(14), after.layout)
        assertEquals((15..28).map(::SeatId).toSet(), after.layout!!.seatIds)
        assertEquals(29, after.nextSeatId)
        assertEquals(listOf("R", "1", "2", "3", "4", "5"), after.layout!!.texts(0))
        assertEquals((10 downTo 3).map { "$it" }, after.layout!!.texts(1))
        assertEquals(7.5f, after.screenWidth)
        assertEquals(1.5f, after.screenDistance)
        // Names and notes are never touched.
        assertEquals("Kleiner Saal", after.name)
        assertEquals("my notes", after.notes)
    }

    @Test
    fun seedingAgainChangesNothing() = runTest {
        apply(seed(1, old))
        apply(seed(2, corrected))
        val replaced = hall()
        assertFalse(apply(seed(2, corrected)))
        assertTrue(apply(seed(3, corrected)))
        assertEquals(replaced, hall())
        // The snapshot now holds the corrected layout: a later, different seed replaces it again.
        assertTrue(apply(seed(4, old)))
        assertEquals(layout(old).withIdsShiftedBy(28), hall().layout)
        assertEquals(43, hall().nextSeatId)
    }

    @Test
    fun anEditedLayoutIsKept() = runTest {
        apply(seed(1, old))
        // Only the numbering direction of one row changed.
        val edited = hall().layout!!.updateRow(1) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
        halls.updateHall(hall().copy(layout = edited))

        apply(seed(2, corrected, width = 7.5f))

        assertEquals(edited, hall().layout)
        assertEquals(10f, hall().screenWidth) // the seed's screen comes only with its layout
    }

    @Test
    fun aLayoutWithVisitsIsKept() = runTest {
        apply(seed(1, old))
        val original = hall().layout
        // Any visit counts, also one without a seat.
        visits.save(Visit(hallId = hall().id, date = LocalDate.of(2026, 10, 1)))

        apply(seed(2, corrected, width = 7.5f))

        assertEquals(original, hall().layout)
        assertEquals(15, hall().nextSeatId)
        assertEquals(10f, hall().screenWidth)
    }

    @Test
    fun aLayoutWithASeatColourIsKept() = runTest {
        apply(seed(1, old))
        val original = hall().layout
        overrides.setSeatColour(hall().id, SeatId(3), SeatColour.RED)
        apply(seed(2, corrected))
        assertEquals(original, hall().layout)
    }

    @Test
    fun aLayoutWithAFavouriteSeatIsKept() = runTest {
        apply(seed(1, old))
        val original = hall().layout
        overrides.setFavouriteSeat(hall().id, SeatId(4))
        apply(seed(2, corrected))
        assertEquals(original, hall().layout)
    }

    @Test
    fun aLayoutKeptOnceIsNoLongerTheSeedsAfterwards() = runTest {
        apply(seed(1, old))
        val original = hall().layout
        val visit = visits.save(Visit(hallId = hall().id, date = LocalDate.of(2026, 10, 1)))
        apply(seed(2, corrected))
        // Without the visit, a later seed still keeps it: it isn't the previous seed's layout any more.
        visits.deleteVisit(visit)
        apply(seed(3, """{"label": "1", "cells": "SSSSSSSS"}"""))
        assertEquals(original, hall().layout)
    }

    @Test
    fun aChangedScreenKeepsTheUsersValuesWhileTheLayoutIsReplaced() = runTest {
        apply(seed(1, old))
        halls.updateHall(hall().copy(screenWidth = 9f, screenOffset = -1.5f))

        apply(seed(2, corrected, width = 7.5f, distance = 1.5f))

        val after = hall()
        assertEquals(correctedLayout.withIdsShiftedBy(14), after.layout)
        assertEquals(9f, after.screenWidth) // the user's value
        assertEquals(1.5f, after.screenDistance) // still the old seed's: follows the new seed
        assertEquals(-1.5f, after.screenOffset) // never touched
    }

    @Test
    fun aLayoutFilledAboveTheMarkIsComparedByStructure() = runTest {
        // Seed 1 has no layout; the user draws, saves and clears one (mark 4).
        apply(seed(1, null))
        halls.updateHall(hall().copy(layout = layoutOf("SSS")))
        halls.updateHall(hall().copy(layout = null))
        assertEquals(4, hall().nextSeatId)
        // Seed 2 fills the empty layout above the mark: ids 4..17.
        apply(seed(2, old))
        assertEquals((4..17).map(::SeatId).toSet(), hall().layout!!.seatIds)

        // Seed 3 still recognises it as its own and replaces it, again above the mark.
        apply(seed(3, corrected))
        assertEquals(correctedLayout.withIdsShiftedBy(17), hall().layout)
        assertEquals(32, hall().nextSeatId)
    }

    @Test
    fun aSeatPaintedOverAndBackStillCountsAsUntouched() = runTest {
        apply(seed(1, old))
        // Same structure, but the redrawn seat got a new id (15).
        val redrawn = hall().layout!!.withCellType(1, 0, CellType.GAP).withCellType(1, 0, CellType.SEAT)
        assertEquals(SeatId(15), redrawn.rows[1].seatIds.first())
        halls.updateHall(hall().copy(layout = redrawn))
        assertEquals(16, hall().nextSeatId)

        apply(seed(2, corrected))

        assertEquals(correctedLayout.withIdsShiftedBy(15), hall().layout)
    }

    @Test
    fun newIdsStayAboveTheMarkAfterUndoneEdits() = runTest {
        apply(seed(1, old))
        // The user adds a column of seats and saves (ids 15, 16), then removes it again.
        val layout = hall().layout!!
        halls.updateHall(hall().copy(layout = layout.insertColumn(8)))
        halls.updateHall(hall().copy(layout = layout))
        assertEquals(17, hall().nextSeatId)

        apply(seed(2, corrected))

        val after = hall()
        assertEquals(correctedLayout.withIdsShiftedBy(16), after.layout)
        assertTrue(after.layout!!.seatIds.all { it.value >= 17 })
        assertEquals(31, after.nextSeatId)
    }

    @Test
    fun aLayoutTheUserDrewIsNeverTheSeeds() = runTest {
        apply(seed(1, null))
        halls.updateHall(hall().copy(layout = layoutOf("SSSSSSSS")))
        apply(seed(2, old)) // the hall's layout isn't empty: kept
        assertEquals(layoutOf("SSSSSSSS"), hall().layout)
        apply(seed(3, corrected)) // and it isn't seed 2's either
        assertEquals(layoutOf("SSSSSSSS"), hall().layout)
    }
}
