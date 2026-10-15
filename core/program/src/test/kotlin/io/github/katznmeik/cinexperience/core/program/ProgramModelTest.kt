package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** The model types refuse values that break the rules, so the UI only ever sees checked data. */
class ProgramModelTest {

    private val start = LocalDateTime.of(2026, 10, 16, 17, 0)
    private val cinema = ProgramCinema.fromYorckName("Odeon")

    @Test
    fun filmsRefuseInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "Bad Slug", title = "T") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = " padded ") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "Zero​width") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", tagline = "") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", genre = "g".repeat(61)) }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", runtimeMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", fsk = 14) }
    }

    @Test
    fun showingsRefuseInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { Showing("1001_1", "ok", start, cinema) }
        assertThrows(IllegalArgumentException::class.java) { Showing("1001-1", "Bad", start, cinema) }
        assertThrows(IllegalArgumentException::class.java) { Showing("1001-1", "ok", start, cinema, eventLabels = listOf("x".repeat(25))) }
        assertThrows(IllegalArgumentException::class.java) { ProgramCinema("", null) }
        assertThrows(IllegalArgumentException::class.java) { ProgramCinema.fromYorckName("\u0000 ") }
    }

    @Test
    fun programsAreConsistent() {
        val film = Film(slug = "ok", title = "T")
        val showing = Showing("1001-1", "ok", start, cinema)
        assertThrows(IllegalArgumentException::class.java) { Program(Instant.EPOCH, listOf(film, film), listOf(showing)) }
        assertThrows(IllegalArgumentException::class.java) { Program(Instant.EPOCH, listOf(film), listOf(showing, showing)) }
        assertThrows(IllegalArgumentException::class.java) { Program(Instant.EPOCH, emptyList(), listOf(showing)) }
        val program = Program(Instant.EPOCH, listOf(film), listOf(showing))
        assertEquals(film, program.filmOf(showing))
    }

    @Test
    fun upcomingUsesBerlinTime() {
        val showing = Showing("1001-1", "ok", start, cinema) // 17:00 Berlin = 15:00 UTC in October
        assertTrue(showing.isUpcoming(Instant.parse("2026-10-16T14:59:00Z")))
        assertFalse(showing.isUpcoming(Instant.parse("2026-10-16T15:00:00Z")))
    }

    @Test
    fun textCleaning() {
        assertEquals("a b", ProgramText.clean("  a \n\t b  "))
        assertEquals("ab", ProgramText.clean("a­‍⁦b⁩"))
        assertEquals("", ProgramText.clean("\u0000\u0001\u001F\u007F\u009F"))
        assertEquals("Café 🎬", ProgramText.clean("Café 🎬"))
        assertEquals(1, ProgramText.length("🎬"))
    }
}
