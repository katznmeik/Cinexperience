package io.github.katznmeik.cinexperience.feature.tickets.importing

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import io.github.katznmeik.cinexperience.core.ticket.PrintedSeat
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfContent
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.DATE
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.INVALID
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.MISSING
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.START
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.cinemas
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching a read ticket with the app's cinemas, halls, seats and the cached program (spec §11.2, Matching). Invented data. */
class TicketMatchingTest {

    private val number = BookingNumber(ImportFixtures.BOOKING_NUMBER)
    private val qr = SampleTickets.qrModules()
    private val international = 7L
    private val kantKino = 8L

    private fun match(
        content: TicketPdfContent = content(),
        cinemas: List<io.github.katznmeik.cinexperience.core.model.CinemaDetail> = cinemas(),
        program: io.github.katznmeik.cinexperience.core.program.Program? = null,
    ) = TicketMatching.match(content, number, qr, cinemas, program)

    private fun seatId(row: Int, seat: Int) = SampleTickets.saal4Layout.rows[row - 1].seatIds[seat - 1]

    @Test
    fun aTicketOfAKnownHallFillsEverything() {
        val result = match()
        assertEquals(SampleTickets.HALL, result.hallId)
        with(result.prefill) {
            assertEquals(SampleData.DELPHI_LUX, cinemaId)
            assertEquals(DATE, date)
            assertEquals(START, startTime)
            assertEquals(SampleTickets.FILM, filmTitle)
            assertEquals("the first seat is the owner's", seatId(7, 10), seatId)
            assertEquals(TicketDraft(number, listOf(TicketSeat("7", "10"), TicketSeat("7", "11")), qr), ticket)
            assertEquals(emptyList<String>(), notes)
            assertNull("no program", format)
        }
    }

    @Test
    fun theCinemaIsFoundAsPrintedOrWithKinoInFront() {
        val printedShort = match(content(cinema = "International", hall = "1"))
        assertEquals(international, printedShort.prefill.cinemaId)
        assertEquals(701L, printedShort.hallId)
        val printedLong = match(content(cinema = "Kino International", hall = "1"))
        assertEquals(international, printedLong.prefill.cinemaId)
        assertEquals("names compare ignoring case and spaces", SampleData.DELPHI_LUX, match(content(cinema = " Delphi  lux ")).prefill.cinemaId)
    }

    @Test
    fun aCinemaTheAppDoesntHaveIsANote() {
        val unknown = match(content(cinema = "Kino Atlantis"))
        assertNull(unknown.prefill.cinemaId)
        assertNull(unknown.hallId)
        assertNull(unknown.prefill.seatId)
        assertEquals(
            listOf(
                "Cinema on the ticket: \u2068Kino Atlantis\u2069 (not in the app)",
                "Hall on the ticket: \u2068Saal 4\u2069",
                "Seats on the ticket: \u2068Row 7 · Seat 10\u2069, \u2068Row 7 · Seat 11\u2069 (not selected: choose the hall first)",
            ),
            unknown.prefill.notes,
        )
        // The rest of the ticket is still there.
        assertEquals(SampleTickets.FILM, unknown.prefill.filmTitle)
        assertEquals(2, unknown.prefill.ticket?.seats?.size)
    }

    @Test
    fun aCinemaTheUserDeletedIsNotMatched() {
        val withoutDelphiLux = cinemas().filter { it.cinema.id != SampleData.DELPHI_LUX }
        val result = match(cinemas = withoutDelphiLux)
        assertNull(result.prefill.cinemaId)
        assertEquals("Cinema on the ticket: \u2068delphi LUX\u2069 (not in the app)", result.prefill.notes.first())
    }

    @Test
    fun aUserCinemaTheSeederAdoptedIsMatchedByItsSeedKey() {
        val adopted = cinemas().map {
            if (it.cinema.id == SampleData.DELPHI_LUX) it.copy(cinema = it.cinema.copy(name = "My delphi", isPreloaded = false)) else it
        }
        assertEquals(SampleData.DELPHI_LUX, match(cinemas = adopted).prefill.cinemaId)
    }

    @Test
    fun hallsAreSaalAndTheirNumberOrNamedAsPrinted() {
        val renamed = cinemas().map { detail ->
            detail.copy(halls = detail.halls.map { if (it.hall.id == 502L) it.copy(hall = it.hall.copy(name = "Luxbox")) else it })
        }
        assertEquals(502L, match(content(hall = "luxbox"), cinemas = renamed).hallId)
        val spaced = cinemas().map { detail ->
            detail.copy(halls = detail.halls.map { if (it.hall.id == 503L) it.copy(hall = it.hall.copy(name = "SAAL   3")) else it })
        }
        assertEquals(503L, match(content(hall = "3"), cinemas = spaced).hallId)
    }

    @Test
    fun aHallTheCinemaDoesntHaveIsANoteAndLeavesTheCinemaSet() {
        val result = match(content(hall = "9"))
        assertNull(result.hallId)
        assertEquals(SampleData.DELPHI_LUX, result.prefill.cinemaId)
        assertNull("no hall, no seat", result.prefill.seatId)
        assertEquals(listOf("Hall on the ticket: \u2068Saal 9\u2069 (not in the app)", "Seats on the ticket: \u2068Row 7 · Seat 10\u2069, \u2068Row 7 · Seat 11\u2069 (not selected: choose the hall first)"), result.prefill.notes)
    }

    @Test
    fun aOneHallCinemaStillNeedsThePrintedHall() {
        val result = match(content(cinema = "Kino International", hall = "2"))
        assertNull("not matched: Add visit selects the only hall itself", result.hallId)
        assertEquals(international, result.prefill.cinemaId)
        assertEquals(listOf("Hall on the ticket: \u2068Saal 2\u2069 (not in the app)", "Seats on the ticket: \u2068Row 7 · Seat 10\u2069, \u2068Row 7 · Seat 11\u2069 (not selected: choose the hall first)"), result.prefill.notes)
        val noHall = match(content(cinema = "Kino International", hall = null))
        assertNull(noHall.hallId)
        assertEquals(listOf("Not on the ticket: hall", "Seats on the ticket: \u2068Row 7 · Seat 10\u2069, \u2068Row 7 · Seat 11\u2069 (not selected: choose the hall first)"), noHall.prefill.notes)
    }

    @Test
    fun seatsInRowsWithoutANameMatchSeatsWithoutARow() {
        val result = match(
            content(cinema = "Kant Kino", hall = "3", seats = listOf(PrintedSeat(null, "12"))),
            cinemas = cinemas(SampleData.storeWithKantKinoSaal3()),
        )
        assertEquals(SampleData.KANT_KINO_SAAL_3, result.hallId)
        assertEquals(kantKino, result.prefill.cinemaId)
        assertEquals(SampleLayouts.kantKinoSaal3Seat12, result.prefill.seatId)
        assertEquals(listOf(TicketSeat(null, "12")), result.prefill.ticket?.seats)
    }

    @Test
    fun fixedLabelsMatchExactly() {
        val store = SampleData.storeWithYorckNumbering()
        val result = match(content(hall = "1", seats = listOf(PrintedSeat("6", "R"))), cinemas = cinemas(store))
        assertEquals(SampleData.DELPHI_LUX_SAAL_1, result.hallId)
        assertEquals(SampleLayouts.yorckRowSixR, result.prefill.seatId)
    }

    @Test
    fun unmatchedSeatsStayOnTheTicketAndSelectNothing() {
        val firstMissing = match(content(seats = listOf(PrintedSeat("7", "99"), PrintedSeat("7", "11"))))
        assertNull("the owner's seat isn't in the layout: none is selected", firstMissing.prefill.seatId)
        assertEquals(listOf(TicketSeat("7", "99"), TicketSeat("7", "11")), firstMissing.prefill.ticket?.seats)
        assertEquals(listOf("Seat on the ticket: \u2068Row 7 · Seat 99\u2069 (not in the hall's layout)"), firstMissing.prefill.notes)

        val both = match(content(seats = listOf(PrintedSeat("12", "1"), PrintedSeat("12", "2"))))
        assertEquals(
            listOf("Seats on the ticket: \u2068Row 12 · Seat 1\u2069, \u2068Row 12 · Seat 2\u2069 (not in the hall's layout)"),
            both.prefill.notes,
        )
    }

    @Test
    fun aHallWithoutALayoutMatchesNoSeats() {
        val result = match(content(hall = "6"))
        assertEquals(SampleData.DELPHI_LUX_SAAL_6, result.hallId)
        assertNull(result.prefill.seatId)
        assertEquals(
            listOf("Seats on the ticket: \u2068Row 7 · Seat 10\u2069, \u2068Row 7 · Seat 11\u2069 (the hall has no seat layout yet)"),
            result.prefill.notes,
        )
    }

    @Test
    fun aHallWhoseLayoutCantBeReadMatchesNoSeatsAndSaysWhy() {
        val unreadable = cinemas().map { detail ->
            detail.copy(halls = detail.halls.map { if (it.hall.id == 504L) it.copy(hall = it.hall.copy(layout = null, layoutUnreadable = true)) else it })
        }
        val result = match(content(hall = "4"), cinemas = unreadable)
        assertEquals(504L, result.hallId)
        assertNull(result.prefill.seatId)
        assertEquals(
            listOf("Seats on the ticket: \u2068Row 7 · Seat 10\u2069, \u2068Row 7 · Seat 11\u2069 (the hall's seat layout can't be read)"),
            result.prefill.notes,
        )
    }

    @Test
    fun whatIsntOnTheTicketOrCantBeReadIsListed() {
        val result = match(
            content(date = null, startTime = null, filmTitle = null).copy(hall = INVALID, seats = MISSING),
        )
        assertEquals(
            listOf("Not on the ticket: date, start time, film title, seats", "Couldn't be read from the ticket: hall"),
            result.prefill.notes,
        )
        assertNull(result.hallId)
        assertEquals(SampleData.DELPHI_LUX, result.prefill.cinemaId)
        assertEquals(TicketDraft(number, emptyList(), qr), result.prefill.ticket)
        assertNull(result.prefill.date)
        assertNull(result.prefill.startTime)
    }

    @Test
    fun theProgramGivesTheFormatOfTheShowingAtThatCinemaAndTime() {
        val program = buildProgram {
            film(SampleTickets.FILM) {
                showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.OMU)
                showing("2026-10-17T20:15", "Kant Kino", ShowingVersion.DF)
                showing("2026-10-17T22:15", "delphi LUX", ShowingVersion.OV)
            }
        }
        assertEquals(ScreeningFormat.OMU, match(program = program).prefill.format)
        assertNull("another time", match(content(startTime = START.plusHours(1)), program = program).prefill.format)
        assertNull("another day", match(content(date = DATE.plusDays(1)), program = program).prefill.format)
        assertNull("without a matched cinema", match(content(cinema = "Kino Atlantis"), program = program).prefill.format)
        // The title stays the ticket's, whatever the program calls the film.
        assertEquals(SampleTickets.FILM, match(program = program).prefill.filmTitle)
    }

    @Test
    fun severalShowingsAtOneTimeAreToldApartByTheFilm() {
        val program = buildProgram {
            film(SampleTickets.FILM) { showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.OV) }
            film("Neon Harbour") { showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.DF) }
        }
        assertEquals(ScreeningFormat.OV, match(program = program).prefill.format)
        assertEquals("the ticket's title, ignoring case", ScreeningFormat.GERMAN_DUB, match(content(filmTitle = "neon harbour"), program = program).prefill.format)
        assertNull("ambiguous without the film", match(content(filmTitle = "Paper Kites"), program = program).prefill.format)
    }

    @Test
    fun showingsThatDisagreeOnTheFormatGiveNone() {
        val program = buildProgram {
            film(SampleTickets.FILM) {
                showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.OV)
                showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.DF)
            }
        }
        assertNull(match(program = program).prefill.format)
        val agreeing = buildProgram {
            film(SampleTickets.FILM) {
                showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.OMU)
                showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.OMU)
            }
        }
        assertEquals(ScreeningFormat.OMU, match(program = agreeing).prefill.format)
        val omeu = buildProgram { film(SampleTickets.FILM) { showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.OMEU) } }
        assertNull("OmeU has no format", match(program = omeu).prefill.format)
    }

    @Test
    fun notesNeverHoldAnythingButPlainText() {
        val notes = match(content(cinema = "Kino Atlantis", hall = "4")).prefill.notes
        assertTrue(notes.all { note -> note.none { it.isISOControl() } })
    }
}
