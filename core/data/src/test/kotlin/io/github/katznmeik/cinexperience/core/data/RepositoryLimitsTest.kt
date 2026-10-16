package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_LONG_TEXT_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_NAME_LENGTH
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The repositories refuse what the backup validator refuses (texts over their
 * limits, a screen without width or behind the first row), so everything the app
 * saves can be exported; see `BackupLimitsTest` for data at the limits.
 */
internal class RepositoryLimitsTest : DatabaseTest() {

    private val name = "N".repeat(MAX_NAME_LENGTH)
    private val longText = "L".repeat(MAX_LONG_TEXT_LENGTH)
    private val date = LocalDate.of(2026, 10, 1)

    /** Runs [write], which must be refused, and returns the refusal's message. */
    private fun refused(write: suspend () -> Unit): String =
        assertThrows(IllegalArgumentException::class.java) { runBlocking { write() } }.message.orEmpty()

    @Test
    fun aCinemaNameOverTheLimitIsRefusedWithAMessage() = runTest {
        val message = refused { cinemas.createCinema(name + "x") }
        assertTrue(message, message.contains("too long"))
        assertTrue(db.cinemaDao().getAll().isEmpty())

        val id = cinemas.createCinema(name)
        assertTrue(refused { cinemas.renameCinema(id, name + "x") }.contains("too long"))
        assertTrue(refused { cinemas.updateCinema(id, name + "x", null, null) }.contains("too long"))
        assertEquals(name, db.cinemaDao().get(id)!!.name)
    }

    @Test
    fun cinemaTextsOverTheirLimitsAreRefused() = runTest {
        refused { cinemas.createCinema("Kino", address = name + "x") }
        refused { cinemas.createCinema("Kino", notes = longText + "x") }
        val id = cinemas.createCinema("Kino", name, longText)
        refused { cinemas.updateCinema(id, "Kino", name + "x", null) }
        refused { cinemas.updateCinema(id, "Kino", null, longText + "x") }
        val stored = db.cinemaDao().get(id)!!
        assertEquals(listOf(name, longText), listOf(stored.address, stored.notes))
    }

    @Test
    fun limitsApplyToTheTrimmedText() = runTest {
        val id = cinemas.createCinema("  $name  ", "\n$name\n", " $longText ")
        val stored = db.cinemaDao().get(id)!!
        assertEquals(listOf(name, name, longText), listOf(stored.name, stored.address, stored.notes))
    }

    @Test
    fun hallTextsOverTheirLimitsAreRefused() = runTest {
        val cinemaId = cinemas.createCinema("Kino")
        refused { halls.createHall(cinemaId, name + "x") }
        val hallId = halls.createHall(cinemaId, name)
        refused { halls.renameHall(hallId, name + "x") }
        val hall = halls.getHall(hallId)!!
        refused { halls.updateHall(hall.copy(name = name + "x")) }
        refused { halls.updateHall(hall.copy(notes = longText + "x")) }
        refused { halls.updateHall(hall.copy(referenceImagePath = "reference/" + "a".repeat(MAX_NAME_LENGTH))) }
        assertTrue(halls.updateHall(hall.copy(notes = longText)))
        assertEquals(longText, halls.getHall(hallId)!!.notes)
    }

    @Test
    fun aScreenNeedsAWidthAndCanNotBeBehindTheFirstRow() = runTest {
        val hallId = halls.createHall(cinemas.createCinema("Kino"), "Saal 1")
        val hall = halls.getHall(hallId)!!
        for (width in listOf(0f, -0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) refused { halls.updateHall(hall.copy(screenWidth = width)) }
        for (distance in listOf(-0.5f, Float.NaN, Float.NEGATIVE_INFINITY)) refused { halls.updateHall(hall.copy(screenDistance = distance)) }
        assertTrue(halls.updateHall(hall.copy(screenWidth = 0.5f, screenDistance = 0f)))
        assertTrue(halls.updateHall(hall.copy(screenWidth = null, screenDistance = null)))
    }

    @Test
    fun visitTextsOverTheirLimitsAreRefused() = runTest {
        val hallId = halls.createHall(cinemas.createCinema("Kino"), "Saal 1")
        refused { visits.saveVisit(Visit(hallId = hallId, date = date, filmTitle = longText + "x")) }
        refused { visits.saveVisit(Visit(hallId = hallId, date = date, note = longText + "x")) }
        refused { visits.saveVisit(Visit(hallId = hallId, date = date, photoPath = "photos/" + "a".repeat(MAX_NAME_LENGTH))) }
        assertTrue(db.backupDao().visits().isEmpty())
        val id = visits.save(Visit(hallId = hallId, date = date, filmTitle = longText, note = longText))
        assertEquals(longText, visits.getVisit(id)!!.filmTitle)
    }
}
