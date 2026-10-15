package io.github.katznmeik.cinexperience.core.program

import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class VisitPrefillTest {

    private fun showing(vararg versions: ShowingVersion, cinema: String = "delphi LUX") = Showing(
        id = "1001-1",
        filmKey = FilmKey(FilmKind.FILM, "the-quiet-hour"),
        start = LocalDateTime.of(2026, 10, 16, 23, 30),
        cinema = ProgramCinema.fromYorckName(cinema),
        versions = versions.toList(),
    )

    @Test
    fun versionsMapToTheVisitsScreeningFormats() {
        assertEquals(ScreeningFormat.GERMAN_DUB, ShowingVersion.DF.toScreeningFormat())
        assertEquals(ScreeningFormat.OMU, ShowingVersion.OMU.toScreeningFormat())
        assertEquals(ScreeningFormat.OV, ShowingVersion.OV.toScreeningFormat())
        assertNull(ShowingVersion.OMEU.toScreeningFormat())
    }

    @Test
    fun aShowingPrefillsItsOneFormat() {
        assertEquals(ScreeningFormat.OMU, showing(ShowingVersion.OMU).screeningFormat())
        assertEquals(ScreeningFormat.GERMAN_DUB, showing(ShowingVersion.DF).screeningFormat())
        assertEquals(ScreeningFormat.OMU, showing(ShowingVersion.OMU, ShowingVersion.OMEU).screeningFormat())
        assertNull(showing(ShowingVersion.OMEU).screeningFormat())
        assertNull(showing().screeningFormat())
        assertNull(showing(ShowingVersion.DF, ShowingVersion.OV).screeningFormat())
    }

    @Test
    fun visitPrefillTakesCinemaDateTitleAndFormat() {
        val film = Film(slug = "the-quiet-hour", title = "The Quiet Hour")
        assertEquals(
            VisitPrefill("delphi-lux", LocalDate.of(2026, 10, 16), "The Quiet Hour", ScreeningFormat.OV),
            showing(ShowingVersion.OV).visitPrefill(film),
        )
        assertEquals(
            VisitPrefill(null, LocalDate.of(2026, 10, 16), null, null),
            showing(cinema = "Kino Unbekannt").visitPrefill(null),
        )
    }

    @Test
    fun aShowingOnlyASpecialListsTakesTheSpecialsTitle() {
        val special = Film(slug = "midnight-surprise", title = "Midnight Surprise", kind = FilmKind.SPECIAL)
        val showing = Showing("1001-9", special.key, LocalDateTime.of(2026, 10, 19, 22, 0), ProgramCinema.fromYorckName("Odeon"), listOf(ShowingVersion.OV))
        val program = Program(java.time.Instant.EPOCH, listOf(special), listOf(showing))
        assertEquals(
            VisitPrefill("odeon", LocalDate.of(2026, 10, 19), "Midnight Surprise", ScreeningFormat.OV),
            showing.visitPrefill(program.filmOf(showing)),
        )
    }
}
