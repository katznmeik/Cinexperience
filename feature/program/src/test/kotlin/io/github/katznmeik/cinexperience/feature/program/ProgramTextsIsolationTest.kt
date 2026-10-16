package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.FilmKey
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Texts from Yorck's page joined with other text are wrapped in Unicode isolates
 * (U+2068 … U+2069), so a right-to-left name can't reorder the times next to it.
 */
class ProgramTextsIsolationTest {

    /** A Hebrew name: right-to-left text, as Yorck's page could carry it. */
    private val hebrew = "\u05E7\u05D5\u05DC\u05E0\u05D5\u05E2"

    @Test
    fun filterSummaryIsolatesASingleCinemaName() {
        val filter = ProgramFilter(
            cinemas = setOf("delphi-lux"),
            from = LocalTime.of(16, 0),
            to = LocalTime.of(17, 30),
            versions = setOf(ShowingVersion.OMU),
        )
        assertEquals(
            "\u2068$hebrew\u2069 · 16:00–17:30 · OmU",
            filterSummary(filter, mapOf("delphi-lux" to hebrew)),
        )
    }

    @Test
    fun filterSummaryOfAnUnknownCinemaIsNotIsolated() {
        // "1 cinema" is the app's own text.
        assertEquals("1 cinema · Any time · All versions", filterSummary(ProgramFilter(cinemas = setOf("x")), emptyMap()))
    }

    @Test
    fun showingSpokenNameIsolatesTitleAndCinema() {
        val item = ShowingItem(
            showingId = "1",
            film = FilmKey(FilmKind.FILM, "film"),
            filmTitle = hebrew,
            start = LocalDateTime.of(2026, 10, 15, 16, 30),
            cinemaName = "delphi LUX",
            cinema = null,
            versions = emptyList(),
            events = emptyList(),
            runtimeMinutes = null,
            endsAbout = null,
        )
        assertEquals(
            "\u2068$hebrew\u2069, Thursday 15 Oct, 16:30, \u2068delphi LUX\u2069",
            showingSpokenName(item),
        )
    }

    @Test
    fun partOfTextIsolatesEveryTitle() {
        assertEquals(
            "Part of: \u2068$hebrew\u2069, \u2068Autumn Classics\u2069",
            partOfText(listOf(hebrew, "Autumn Classics")),
        )
    }
}
