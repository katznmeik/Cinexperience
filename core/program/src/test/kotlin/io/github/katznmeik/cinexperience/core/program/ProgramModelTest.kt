package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** The model types refuse values that break the rules, so the UI only ever sees checked data. */
class ProgramModelTest {

    private val start = LocalDateTime.of(2026, 10, 16, 17, 0)
    private val cinema = ProgramCinema.fromYorckName("Odeon")
    private val ok = FilmKey(FilmKind.FILM, "ok")
    private val series = FilmKey(FilmKind.SPECIAL, "ok") // same page name, other kind

    private fun showing(id: String = "1001-1", at: LocalDateTime = start, film: FilmKey = ok, cinema: ProgramCinema = this.cinema, specials: List<FilmKey> = emptyList(), labels: List<String> = emptyList()) =
        Showing(id, film, at, cinema, eventLabels = labels, specialKeys = specials)

    @Test
    fun filmsRefuseInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "Bad Slug", title = "T") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = " padded ") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "Zero\u200Bwidth") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", tagline = "") }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", genre = "g".repeat(61)) }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", runtimeMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { Film(slug = "ok", title = "T", fsk = 14) }
        assertThrows(IllegalArgumentException::class.java) { FilmKey(FilmKind.FILM, "Bad") }
        assertEquals(series, Film(slug = "ok", title = "T", kind = FilmKind.SPECIAL).key)
    }

    @Test
    fun showingsRefuseInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { showing(id = "1001_1") }
        assertThrows(IllegalArgumentException::class.java) { showing(labels = listOf("x".repeat(25))) }
        assertThrows(IllegalArgumentException::class.java) { showing(specials = listOf(ok)) } // not a special
        assertThrows(IllegalArgumentException::class.java) { showing(specials = listOf(series, series)) }
        assertThrows(IllegalArgumentException::class.java) { showing(film = series, specials = listOf(series)) }
        assertThrows(IllegalArgumentException::class.java) { ProgramCinema("", null) }
        assertThrows(IllegalArgumentException::class.java) { ProgramCinema.fromYorckName("\u0000 ") }
    }

    @Test
    fun programsAreConsistent() {
        val film = Film(slug = "ok", title = "T")
        val special = Film(slug = "ok", title = "Series", kind = FilmKind.SPECIAL)
        val one = showing()
        assertThrows(IllegalArgumentException::class.java) { Program(Instant.EPOCH, listOf(film, film), listOf(one)) }
        assertThrows(IllegalArgumentException::class.java) { Program(Instant.EPOCH, listOf(film), listOf(one, one)) }
        assertThrows(IllegalArgumentException::class.java) { Program(Instant.EPOCH, emptyList(), listOf(one)) }
        // A linked special must be in the program.
        assertThrows(IllegalArgumentException::class.java) { Program(Instant.EPOCH, listOf(film), listOf(showing(specials = listOf(series)))) }
        // Showings must be sorted by start.
        assertThrows(IllegalArgumentException::class.java) {
            Program(Instant.EPOCH, listOf(film), listOf(showing("1-2", start.plusHours(1)), showing("1-1", start)))
        }
        val program = Program(Instant.EPOCH, listOf(film, special), listOf(showing(specials = listOf(series))))
        assertEquals(film, program.filmOf(program.showings.single()))
        assertEquals(special, program.film(series))
        assertEquals(1, program.showingsOf(series).size)
        assertEquals(1, program.showingsOf(ok).size)
    }

    @Test
    fun upcomingUsesBerlinTime() {
        val showing = showing() // 17:00 Berlin = 15:00 UTC in October
        assertTrue(showing.isUpcoming(Instant.parse("2026-10-16T14:59:00Z")))
        assertFalse(showing.isUpcoming(Instant.parse("2026-10-16T15:00:00Z")))
    }

    @Test
    fun clockChangesFollowTheDocumentedRule() {
        // 29 Mar 2026, 02:30 doesn't exist in Berlin: moved forward by the gap, to 03:30 summer time.
        val gap = showing(at = LocalDateTime.of(2026, 3, 29, 2, 30))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), gap.startInstant)
        // 25 Oct 2026, 02:30 exists twice: the earlier one, still summer time.
        val overlap = showing(at = LocalDateTime.of(2026, 10, 25, 2, 30))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), overlap.startInstant)
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), overlap.start) // the wall-clock time stays as Yorck shows it
    }

    @Test
    fun endsAboutCountsTheRealTimeAcrossClockChanges() {
        val film = Film(slug = "ok", title = "T", runtimeMinutes = 120)
        // Autumn: 01:30 summer time + 2 h = 02:30 winter time (the clocks went back an hour).
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), showing(at = LocalDateTime.of(2026, 10, 25, 1, 30)).endsAbout(film))
        // Spring: 01:30 winter time + 2 h = 04:30 summer time (the clocks went forward an hour).
        assertEquals(LocalDateTime.of(2026, 3, 29, 4, 30), showing(at = LocalDateTime.of(2026, 3, 29, 1, 30)).endsAbout(film))
        // An ordinary evening.
        assertEquals(LocalDateTime.of(2026, 10, 16, 19, 0), showing().endsAbout(film))
        assertNull(showing().endsAbout(Film(slug = "ok", title = "T")))
        assertNull(showing().endsAbout(null))
    }

    @Test
    fun helpersForTheUi() {
        val film = Film(slug = "ok", title = "T")
        val special = Film(slug = "ok", title = "Series", kind = FilmKind.SPECIAL)
        val delphi = ProgramCinema.fromYorckName("delphi LUX")
        val unknown = ProgramCinema.fromYorckName("Kino Unbekannt")
        val showings = listOf(
            showing("1-1", LocalDateTime.of(2026, 10, 15, 9, 0), cinema = delphi, labels = listOf("Sneak")),
            showing("1-2", LocalDateTime.of(2026, 10, 15, 18, 0), cinema = delphi, labels = listOf("Kids", "Sneak")),
            showing("1-3", LocalDateTime.of(2026, 10, 16, 0, 30), cinema = delphi, specials = listOf(series)),
            showing("1-4", LocalDateTime.of(2026, 10, 21, 23, 0), cinema = delphi),
            showing("1-5", LocalDateTime.of(2026, 10, 22, 10, 0), cinema = delphi),
            showing("1-6", LocalDateTime.of(2026, 10, 22, 11, 0), film = series, cinema = unknown),
        )
        val program = Program(Instant.EPOCH, listOf(film, special), showings)
        val now = Instant.parse("2026-10-15T08:00:00Z") // Thu 15 Oct, 10:00 in Berlin

        assertEquals(listOf("1-2", "1-3", "1-4", "1-5", "1-6"), program.upcoming(now).map { it.id })
        assertEquals(listOf("1-3", "1-6"), program.showingsOf(series).map { it.id })
        assertEquals(listOf(delphi, unknown), program.cinemas)
        assertEquals(listOf("delphi-lux", "Kino Unbekannt"), program.cinemas.map { it.key })
        assertEquals(listOf("Kids", "Sneak"), program.eventLabels)
        // Today and the next 6 days (until Wed 21 Oct), from now on, by Berlin date.
        val week = program.nowShowing("delphi-lux", now)
        assertEquals(
            mapOf(
                LocalDate.of(2026, 10, 15) to listOf("1-2"),
                LocalDate.of(2026, 10, 16) to listOf("1-3"),
                LocalDate.of(2026, 10, 21) to listOf("1-4"),
            ),
            week.mapValues { (_, list) -> list.map { it.id } },
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 15)), program.nowShowing("delphi-lux", now, days = 1).keys.toList())
        assertTrue(program.nowShowing("odeon", now).isEmpty())
    }

    @Test
    fun textCleaning() {
        assertEquals("a b", ProgramText.clean("  a \n\t b  "))
        assertEquals("ab", ProgramText.clean("a\u00AD\u200D\u2066b\u2069"))
        assertEquals("", ProgramText.clean("\u0000\u0001\u001F\u007F\u009F"))
        assertEquals("Café 🎬", ProgramText.clean("Café 🎬"))
        assertEquals(1, ProgramText.length("🎬"))
    }
}
