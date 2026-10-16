package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The screen width and distance of a replaced seed layout (spec §9) follow the new
 * seed only while they still hold what the seeder last wrote into the hall
 * (snapshot fields `appliedScreenWidth`, `appliedScreenDistance`), not merely the
 * previous seed's values: a seed version whose layout was blocked never wrote its
 * screen, and a value the user set stays the user's even if a seed ships it later.
 */
internal class SeedAppliedScreenTest : DatabaseTest() {

    private fun seed(version: Int, rows: String, width: Float, distance: Float) = """
        {"seedVersion": $version, "cinemas": [
          {"seedKey": "alpha", "name": "Alpha", "halls": [
            {"seedKey": "alpha/1", "name": "Saal 1", "screenWidth": $width, "screenDistance": $distance,
             "layout": {"formatVersion": 3, "rows": [$rows]}}
          ]}
        ]}
    """

    /** 14 seats, ids 1..14, 8 columns. */
    private val old = """{"label": "1", "cells": "W.SSSS.W"}, {"label": "2", "cells": "SSSSSSSS"}"""
    private val corrected = """{"label": "1", "cells": "W.SSSS.W"}, {"label": "2", "cells": "SSSSSSSS", "direction": "RIGHT_TO_LEFT"}"""
    private val correctedLayout: SeatLayout = LayoutJson.decode("""{"formatVersion": 3, "rows": [$corrected]}""")

    private suspend fun apply(json: String) = Seeder(db) { json }.seedIfNeeded()

    private suspend fun hall(): Hall = halls.getHall(db.hallDao().getAll().single().id)!!

    private suspend fun snapshot(field: String): String? =
        db.seedSnapshotDao().getAll().single { it.seedKey == "alpha/1" && it.field == field }.value

    /** The state of an install seeded before the applied screen fields existed. */
    private fun forgetAppliedScreen() {
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM seed_snapshot WHERE field IN ('appliedScreenWidth', 'appliedScreenDistance')",
        )
    }

    @Test
    fun aBlockedReplacementLaterBringsTheNewSeedsScreen() = runTest {
        apply(seed(1, old, width = 10f, distance = 2f))
        overrides.setFavouriteSeat(hall().id, SeatId(4))
        // Blocked by the favourite twice: the hall keeps seed 1's layout and screen.
        apply(seed(2, corrected, width = 7.5f, distance = 1.5f))
        apply(seed(3, corrected, width = 8f, distance = 1f))
        assertEquals(10f, hall().screenWidth)

        overrides.setFavouriteSeat(hall().id, null)
        apply(seed(4, corrected, width = 8f, distance = 1f))

        // The screen is still the one seed 1 wrote, so it follows the new layout (it is
        // not seed 3's, which never reached the hall, and it is not the user's).
        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
        assertEquals(8f, hall().screenWidth)
        assertEquals(1f, hall().screenDistance)
        assertEquals("8.0", snapshot("appliedScreenWidth"))
        assertEquals("1.0", snapshot("appliedScreenDistance"))
    }

    @Test
    fun aBlockedReplacementRecordsNoScreen() = runTest {
        apply(seed(1, old, width = 10f, distance = 2f))
        overrides.setFavouriteSeat(hall().id, SeatId(4))
        apply(seed(2, corrected, width = 7.5f, distance = 1.5f))
        assertEquals("10.0", snapshot("appliedScreenWidth"))
        assertEquals("2.0", snapshot("appliedScreenDistance"))
        assertEquals("7.5", snapshot("screenWidth")) // the seed's own record moves on
    }

    @Test
    fun aValueTheUserSetIsKeptEvenWhenALaterSeedShipsIt() = runTest {
        apply(seed(1, old, width = 10f, distance = 2f))
        halls.updateHall(hall().copy(screenWidth = 9f))
        // Seed 2 happens to ship the user's width, with the same layout: nothing is written.
        apply(seed(2, old, width = 9f, distance = 2f))

        apply(seed(3, corrected, width = 7.5f, distance = 1.5f))

        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
        assertEquals(9f, hall().screenWidth) // the user's, though seed 2 had it too
        assertEquals(1.5f, hall().screenDistance) // still seed 1's: follows
        assertEquals("10.0", snapshot("appliedScreenWidth"))
        assertEquals("1.5", snapshot("appliedScreenDistance"))
    }

    @Test
    fun anAdoptedHallsOwnScreenIsNeverTheSeeds() = runTest {
        // The user's hall without a layout, with seed 1's width typed in by hand.
        val hallId = halls.createHall(cinemas.createCinema("Alpha"), "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(screenWidth = 10f))

        apply(seed(1, old, width = 10f, distance = 2f)) // adopted: layout and distance filled
        apply(seed(2, corrected, width = 7.5f, distance = 1.5f)) // the layout the seeder wrote is replaced

        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
        assertEquals(10f, hall().screenWidth) // the user's
        assertEquals(1.5f, hall().screenDistance) // the seeder's: follows
        assertNull(snapshot("appliedScreenWidth")) // the seeder never wrote one
        assertEquals("1.5", snapshot("appliedScreenDistance"))
    }

    @Test
    fun installsSeededBeforeTheFieldsFallBackToThePreviousSeedsValues() = runTest {
        apply(seed(1, old, width = 10f, distance = 2f))
        forgetAppliedScreen()
        halls.updateHall(hall().copy(screenDistance = 3f))

        apply(seed(2, corrected, width = 7.5f, distance = 1.5f))

        // As before the fields existed: the width still holds seed 1's value and follows,
        // the distance the user changed is kept; from then on the fields are recorded.
        assertEquals(correctedLayout.withIdsShiftedBy(14), hall().layout)
        assertEquals(7.5f, hall().screenWidth)
        assertEquals(3f, hall().screenDistance)
        assertEquals("7.5", snapshot("appliedScreenWidth"))
        assertEquals("2.0", snapshot("appliedScreenDistance"))
    }
}
