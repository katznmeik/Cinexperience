package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ProgramLinksTest {

    private val film = Film(slug = "the-quiet-hour", title = "The Quiet Hour")
    private val special = Film(slug = "night-of-reels", title = "Night of Reels", kind = FilmKind.SPECIAL)
    private val showing = Showing(
        id = "1001-89886",
        filmKey = FilmKey(FilmKind.FILM, "the-quiet-hour"),
        start = LocalDateTime.of(2026, 10, 16, 17, 0),
        cinema = ProgramCinema.fromYorckName("delphi LUX"),
    )

    @Test
    fun linksFollowTheFixedPatterns() {
        assertEquals("https://www.yorck.de/en/checkout/seats?sessionid=1001-89886", ProgramLinks.showing("1001-89886"))
        assertEquals("https://www.yorck.de/en/films/the-quiet-hour", ProgramLinks.film("the-quiet-hour"))
        assertEquals("https://www.yorck.de/en/specials/night-of-reels", ProgramLinks.special("night-of-reels"))
        assertEquals("https://www.yorck.de/en/films/the-quiet-hour", ProgramLinks.page(film))
        assertEquals("https://www.yorck.de/en/specials/night-of-reels", ProgramLinks.page(special))
    }

    @Test
    fun invalidIdsGiveNoLink() {
        for (id in listOf("", "1001", "1001-", "abc-1", "1001-1&x=1", "1001-1#a", "1001-1/../x", " 1001-1", "1001-1\n", "1234567-1", "1-1234567890123")) {
            assertNull(id, ProgramLinks.showing(id))
        }
    }

    @Test
    fun invalidSlugsGiveNoLink() {
        for (slug in listOf("", "A", "a/b", "../admin", "a?b", "a#b", "a b", "a--b", "-a", "https://evil.example", "x".repeat(121))) {
            assertNull(slug, ProgramLinks.film(slug))
            assertNull(slug, ProgramLinks.special(slug))
        }
    }

    @Test
    fun bookingFallsBackToTheFilmPage() {
        assertEquals("https://www.yorck.de/en/checkout/seats?sessionid=1001-89886", ProgramLinks.booking(showing, film))
        assertEquals("https://www.yorck.de/en/checkout/seats?sessionid=1001-89886", ProgramLinks.booking(showing, null))
        // An unusable ID (the model never holds one; defence in depth): the film's page, or nothing.
        assertEquals("https://www.yorck.de/en/films/the-quiet-hour", ProgramLinks.booking("1001-89886&x=1", film))
        assertEquals("https://www.yorck.de/en/specials/night-of-reels", ProgramLinks.booking("x", special))
        assertNull(ProgramLinks.booking("x", null))
    }
}
