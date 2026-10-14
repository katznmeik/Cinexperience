package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.db.SeedSnapshotEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** The `appliedLayout` snapshot row of the hall, `null` if there is none. */
    private suspend fun appliedRow(): SeedSnapshotEntity? =
        db.seedSnapshotDao().getAll().singleOrNull { it.seedKey == "alpha/1" && it.field == "appliedLayout" }

    /** The state of an install seeded before `appliedLayout` existed: only the `layout` snapshot. */
    private fun forgetAppliedLayout() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM seed_snapshot WHERE field = 'appliedLayout'")
    }

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
        val snapshots = db.seedSnapshotDao().getAll().toSet()
        assertFalse(apply(seed(2, corrected)))
        assertTrue(apply(seed(3, corrected)))
        assertEquals(replaced, hall())
        assertEquals(snapshots, db.seedSnapshotDao().getAll().toSet())
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
    fun aLayoutKeptOnceIsStillTheSeedsAfterwards() = runTest {
        apply(seed(1, old))
        val original = hall().layout
        val visit = visits.save(Visit(hallId = hall().id, date = LocalDate.of(2026, 10, 1)))
        apply(seed(2, corrected))
        assertEquals(original, hall().layout)
        // Without the visit, a later seed replaces it: it is still exactly the layout seed 1 wrote,
        // even though seed 2 (whose layout it is not) came in between.
        visits.deleteVisit(visit)
        apply(seed(3, """{"label": "1", "cells": "SSSSSSSS"}"""))
        assertEquals(layout("""{"label": "1", "cells": "SSSSSSSS"}""").withIdsShiftedBy(14), hall().layout)
    }

    @Test
    fun aReplacementBlockedByAFavouriteHappensOnceTheFavouriteIsRemoved() = runTest {
        apply(seed(1, old))
        val original = hall().layout
        overrides.setFavouriteSeat(hall().id, SeatId(4))
        apply(seed(2, corrected))
        assertEquals(original, hall().layout)
        // Still blocked: seeding again changes nothing.
        apply(seed(3, corrected))
        assertEquals(original, hall().layout)

        overrides.setFavouriteSeat(hall().id, null)
        // The next seed version, with the same corrected layout, replaces it.
        assertTrue(apply(seed(4, corrected)))
        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
        assertEquals(29, hall().nextSeatId)
        assertEquals(listOf("R", "1", "2", "3", "4", "5"), hall().layout!!.texts(0))
    }

    @Test
    fun anAdoptedHallsOwnDrawingIsNeverReplaced() = runTest {
        // The user drew exactly the layout seed 1 will ship, in a hall seed 1 adopts by name.
        val cinemaId = cinemas.createCinema("Alpha")
        val hallId = halls.createHall(cinemaId, "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = layout(old)))
        val drawn = hall().layout

        apply(seed(1, old))
        assertEquals("alpha/1", db.hallDao().getAll().single().seedKey)
        assertEquals(drawn, hall().layout)
        assertNotNull(appliedRow())
        assertNull(appliedRow()!!.value) // the seeder wrote no layout into this hall

        // Equal to seed 1's layout, but the user's: seed 2's layout doesn't replace it.
        apply(seed(2, corrected))
        assertEquals(drawn, hall().layout)
        apply(seed(3, """{"label": "1", "cells": "SSSSSSSS"}"""))
        assertEquals(drawn, hall().layout)
        assertNull(appliedRow()!!.value)
    }

    @Test
    fun anAdoptedHallWithoutALayoutIsFilledAndThenUpdated() = runTest {
        val cinemaId = cinemas.createCinema("Alpha")
        halls.createHall(cinemaId, "Saal 1")
        apply(seed(1, old)) // adopted and filled: the seeder wrote this layout
        assertEquals(layout(old), hall().layout)
        apply(seed(2, corrected))
        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
    }

    @Test
    fun appliedLayoutIsTheCanonicalLayoutTheSeederWrote() = runTest {
        apply(seed(1, null))
        assertNull(appliedRow()!!.value) // inserted without a layout
        halls.updateHall(hall().copy(layout = layoutOf("SSS")))
        halls.updateHall(hall().copy(layout = null))
        apply(seed(2, old)) // filled above the mark (ids 4..17), recorded with ids from 1
        assertEquals((4..17).map(::SeatId).toSet(), hall().layout!!.seatIds)
        assertEquals(layout(old), LayoutJson.decode(appliedRow()!!.value!!))
        apply(seed(3, corrected)) // replaced
        assertEquals(correctedLayout, LayoutJson.decode(appliedRow()!!.value!!))
    }

    @Test
    fun installsSeededBeforeAppliedLayoutFallBackToThePreviousSeedsLayout() = runTest {
        // As before the field existed: an untouched layout of the previous seed is replaced ...
        apply(seed(1, old))
        forgetAppliedLayout()
        assertNull(appliedRow())
        apply(seed(2, corrected, width = 7.5f))
        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
        assertEquals(7.5f, hall().screenWidth)
        // ... and from then on the field is there.
        assertEquals(correctedLayout, LayoutJson.decode(appliedRow()!!.value!!))
    }

    @Test
    fun installsSeededBeforeAppliedLayoutKeepEditedLayouts() = runTest {
        apply(seed(1, old))
        val edited = hall().layout!!.updateRow(1) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
        halls.updateHall(hall().copy(layout = edited))
        forgetAppliedLayout()
        apply(seed(2, corrected))
        assertEquals(edited, hall().layout)
        // The fallback is recorded: seed 1's layout stays the reference, so later seeds keep it too.
        assertEquals(layout(old), LayoutJson.decode(appliedRow()!!.value!!))
        apply(seed(3, """{"label": "1", "cells": "SSSSSSSS"}"""))
        assertEquals(edited, hall().layout)
    }

    @Test
    fun installsSeededBeforeAppliedLayoutStillGetABlockedReplacementLater() = runTest {
        apply(seed(1, old))
        forgetAppliedLayout()
        overrides.setFavouriteSeat(hall().id, SeatId(4))
        val original = hall().layout
        apply(seed(2, corrected)) // blocked; seed 1's layout is recorded as the one written
        assertEquals(original, hall().layout)
        assertEquals(layout(old), LayoutJson.decode(appliedRow()!!.value!!))

        overrides.setFavouriteSeat(hall().id, null)
        apply(seed(3, corrected))
        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
    }

    @Test
    fun installsSeededBeforeAppliedLayoutNeverReplaceADrawnLayout() = runTest {
        apply(seed(1, null))
        halls.updateHall(hall().copy(layout = layoutOf("SSSSSSSS")))
        forgetAppliedLayout()
        apply(seed(2, old)) // not empty: kept, and no seed layout was written before
        assertNull(appliedRow()!!.value)
        apply(seed(3, corrected))
        assertEquals(layoutOf("SSSSSSSS"), hall().layout)
    }

    @Test
    fun aNarrowerReplacementKeepsTheScreenOverTheGrid() = runTest {
        // 8 columns: the user moved the screen 3.5 seats right (the most is 4).
        apply(seed(1, old))
        halls.updateHall(hall().copy(screenOffset = 3.5f))

        // 5 columns: at most 2.5 seats to either side.
        val narrow = """{"label": "1", "cells": "W.S.W"}, {"label": "2", "cells": "SSSSS"}"""
        apply(seed(2, narrow))
        assertEquals(layout(narrow).withIdsShiftedBy(14), hall().layout)
        assertEquals(2.5f, hall().screenOffset)

        // An offset that still fits is kept, also to the left.
        halls.updateHall(hall().copy(screenOffset = -1.5f))
        apply(seed(3, """{"label": "1", "cells": "SSSS"}"""))
        assertEquals(-1.5f, hall().screenOffset)
        // A wider replacement never moves it.
        halls.updateHall(hall().copy(screenOffset = -2f))
        apply(seed(4, old))
        assertEquals(-2f, hall().screenOffset)
        // Nor does a kept layout, however narrow the seed's.
        halls.updateHall(hall().copy(screenOffset = 4f))
        visits.save(Visit(hallId = hall().id, date = LocalDate.of(2026, 10, 1)))
        apply(seed(5, """{"label": "1", "cells": "SS"}"""))
        assertEquals(4f, hall().screenOffset)
        assertEquals(8, hall().layout!!.columnCount)
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
