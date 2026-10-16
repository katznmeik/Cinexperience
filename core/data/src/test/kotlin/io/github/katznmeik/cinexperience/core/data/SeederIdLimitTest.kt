package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hall whose seat id mark leaves no room below [MAX_SEAT_ID] for a seed
 * layout's ids (only damaged or crafted data has one) keeps its layout; the rest
 * of the seed still applies, now and with every later seed version.
 */
internal class SeederIdLimitTest : DatabaseTest() {

    /** The seed: halls `alpha/1` … `alpha/[halls]`, with [layout] (or none), plus a new hall `alpha/v<version>`. */
    private fun seed(version: Int, halls: Int, layout: String?) = buildString {
        append("{\"seedVersion\": $version, \"cinemas\": [{\"seedKey\": \"alpha\", \"name\": \"Alpha\", \"halls\": [")
        for (i in 1..halls) {
            append("{\"seedKey\": \"alpha/$i\", \"name\": \"Saal $i\"")
            layout?.let { append(", \"layout\": {\"formatVersion\": 1, \"rows\": [{\"cells\": \"$it\"}]}") }
            append("},")
        }
        append("{\"seedKey\": \"alpha/v$version\", \"name\": \"New $version\"}]}]}")
    }

    private suspend fun hall(key: String) = db.hallDao().getAll().single { it.seedKey == key }

    private suspend fun setMark(key: String, mark: Int) {
        db.hallDao().update(hall(key).copy(nextSeatId = mark))
    }

    @Test
    fun anEmptyLayoutIsFilledOnlyIfItsIdsStayBelowTheLimit() = runTest {
        val marks = listOf(Int.MAX_VALUE, MAX_SEAT_ID + 1, MAX_SEAT_ID, MAX_SEAT_ID - 2, MAX_SEAT_ID - 3)
        assertTrue(Seeder(db) { seed(1, marks.size, layout = null) }.seedIfNeeded())
        marks.forEachIndexed { i, mark -> setMark("alpha/${i + 1}", mark) }

        assertTrue(Seeder(db) { seed(2, marks.size, layout = "SSS") }.seedIfNeeded())

        // No room for three new ids below the limit: no layout, the mark unchanged.
        for (i in 1..4) {
            assertNull("alpha/$i", hall("alpha/$i").layoutJson)
            assertEquals(marks[i - 1], hall("alpha/$i").nextSeatId)
        }
        // Just enough room: ids up to MAX_SEAT_ID - 1.
        val filled = LayoutJson.decode(hall("alpha/5").layoutJson!!)
        assertEquals(MAX_SEAT_ID, filled.nextSeatId)
        assertEquals(MAX_SEAT_ID, hall("alpha/5").nextSeatId)
        // The rest of the seed applied.
        assertEquals("New 2", hall("alpha/v2").name)
        assertEquals("2", db.metadataDao().get(Seeder.KEY_SEED_VERSION))
    }

    @Test
    fun anUntouchedLayoutIsReplacedOnlyIfItsIdsStayBelowTheLimit() = runTest {
        assertTrue(Seeder(db) { seed(1, 3, layout = "SSS") }.seedIfNeeded())
        val written = hall("alpha/1").layoutJson
        setMark("alpha/1", Int.MAX_VALUE)
        setMark("alpha/2", MAX_SEAT_ID)
        setMark("alpha/3", MAX_SEAT_ID - 4)

        assertTrue(Seeder(db) { seed(2, 3, layout = "SSSS") }.seedIfNeeded())

        assertEquals(written, hall("alpha/1").layoutJson)
        assertEquals(written, hall("alpha/2").layoutJson)
        assertEquals(MAX_SEAT_ID, LayoutJson.decode(hall("alpha/3").layoutJson!!).nextSeatId)
        assertEquals("New 2", hall("alpha/v2").name)
        assertEquals("2", db.metadataDao().get(Seeder.KEY_SEED_VERSION))
    }
}
