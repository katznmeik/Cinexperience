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
        filmSlug = "the-quiet-hour",
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
}
