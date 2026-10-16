package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_LONG_TEXT_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_NAME_LENGTH
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `updateHall` refuses new row labels over 16 code points and screens beyond the
 * editor's largest values, like the backup validator; values a hall already has
 * (stored by an older build) are kept as they are, so they never make a save
 * fail and the layout editor can still save that hall.
 */
internal class HallUpdateLimitsTest : DatabaseTest() {

    /** U+1F3AC CLAPPER BOARD: one code point, two chars. */
    private val emoji = "🎬"

    private fun refused(write: suspend () -> Unit): String =
        assertThrows(IllegalArgumentException::class.java) { runBlocking { write() } }.message.orEmpty()

    private suspend fun hallWithLayout(): Hall {
        val hallId = halls.createHall(cinemas.createCinema("Kino"), "Saal 1")
        val empty = halls.getHall(hallId)!!
        assertTrue(halls.updateHall(empty.copy(layout = empty.startLayoutEdit().newGrid(2, 3).layout)))
        return halls.getHall(hallId)!!
    }

    private fun Hall.withRowLabel(label: String?): Hall = copy(layout = layout!!.updateRow(1) { it.copy(label = label) })

    @Test
    fun aNewRowLabelOverSixteenCodePointsIsRefused() = runTest {
        val hall = hallWithLayout()
        for (label in listOf("R".repeat(17), emoji.repeat(17), "R".repeat(250_000))) {
            val message = refused { halls.updateHall(hall.withRowLabel(label)) }
            assertTrue(message, message.contains("Row label is too long"))
        }
        assertEquals(hall, halls.getHall(hall.id))

        assertTrue(halls.updateHall(hall.withRowLabel(emoji.repeat(16))))
        assertEquals(emoji.repeat(16), halls.getHall(hall.id)!!.layout!!.rows[1].label)
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.withRowLabel("R".repeat(16))))
    }

    @Test
    fun aScreenBeyondTheEditorsLargestValuesIsRefused() = runTest {
        val hall = hallWithLayout()
        for (width in listOf(120.5f, 3e38f, Float.MAX_VALUE)) {
            assertTrue(refused { halls.updateHall(hall.copy(screenWidth = width)) }.contains("Invalid screen width"))
        }
        for (distance in listOf(15.5f, 3e38f, Float.MAX_VALUE)) {
            assertTrue(refused { halls.updateHall(hall.copy(screenDistance = distance)) }.contains("Invalid screen distance"))
        }
        assertTrue(halls.updateHall(hall.copy(screenWidth = 120f, screenDistance = 15f)))
        val stored = halls.getHall(hall.id)!!
        assertEquals(120f, stored.screenWidth)
        assertEquals(15f, stored.screenDistance)
    }

    @Test
    fun valuesStoredBeforeTheLimitsAreKeptWhenTheLayoutIsSaved() = runTest {
        val hall = hallWithLayout()
        // What an older build could have imported: texts, a row label and a screen beyond today's limits.
        val longName = "N".repeat(MAX_NAME_LENGTH + 1)
        val longNotes = "L".repeat(MAX_LONG_TEXT_LENGTH + 1)
        val longLabel = "R".repeat(40)
        val legacyLayout = hall.layout!!.updateRow(0) { it.copy(label = longLabel) }
        db.hallDao().update(
            db.hallDao().get(hall.id)!!.copy(
                name = longName, notes = longNotes, screenWidth = 3e38f, screenDistance = 40f,
                layoutJson = LayoutJson.encode(legacyLayout),
            ),
        )
        val legacy = halls.getHall(hall.id)!!

        // The layout editor saves a changed layout with everything else as read.
        val edited = legacy.startLayoutEdit().setCellType(1, 2, CellType.GAP).layout!!
        assertTrue(halls.updateHall(legacy.copy(layout = edited)))
        val saved = halls.getHall(hall.id)!!
        assertEquals(edited.rows, saved.layout!!.rows)
        assertEquals(listOf(longName, longNotes), listOf(saved.name, saved.notes))
        assertEquals(3e38f, saved.screenWidth)
        assertEquals(40f, saved.screenDistance)
        assertEquals(longLabel, saved.layout!!.rows[0].label)

        // A changed value must fit, also a different long one.
        refused { halls.updateHall(saved.copy(name = longName + "x")) }
        refused { halls.updateHall(saved.copy(notes = longNotes + "x")) }
        refused { halls.updateHall(saved.copy(screenWidth = 2e38f)) }
        refused { halls.updateHall(saved.copy(screenDistance = 41f)) }
        refused { halls.updateHall(saved.withRowLabel(longLabel + "x")) }
        // Blank stays refused, and fitting values repair the hall.
        refused { halls.updateHall(saved.copy(name = " ")) }
        assertTrue(halls.updateHall(saved.copy(name = "Saal 1", notes = null, screenWidth = 4f, screenDistance = 2f)))
        assertEquals("Saal 1", halls.getHall(hall.id)!!.name)
    }
}
