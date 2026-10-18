package io.github.katznmeik.cinexperience.reminders

import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The runtime of a visit's showing in the program (spec §11.3 rule 2): the showing at
 * the visit's cinema (seed key) with the same Berlin start, as the ticket import matches
 * it (§11.2). Invented films.
 */
class ReminderRuntimesTest {

    private val delphiLux = 5L
    private val seedKeys = mapOf(delphiLux to "delphi-lux", 1L to "babylon-kreuzberg")

    private val program = buildProgram {
        film("The Quiet Projectionist", runtimeMinutes = 104) {
            showing("2026-10-17T20:15", "delphi LUX")
            showing("2026-10-17T22:30", "delphi LUX")
        }
        film("Paper Kites", runtimeMinutes = 97) {
            showing("2026-10-17T22:30", "delphi LUX")
            showing("2026-10-17T20:15", "Babylon Kreuzberg")
        }
        film("No Runtime Given") {
            showing("2026-10-18T18:00", "delphi LUX")
        }
    }

    private fun item(id: Long, start: String?, cinemaId: Long = delphiLux, film: String? = null, date: String = "2026-10-17") = VisitListItem(
        Visit(id = id, hallId = cinemaId * 100 + 1, date = LocalDate.parse(date), startTime = start?.let(LocalTime::parse), filmTitle = film),
        cinemaId = cinemaId,
        cinemaName = "Cinema",
        hallName = "Saal 1",
        seatLabel = null,
    )

    @Test
    fun theShowingAtTheCinemaAndStartGivesTheRuntime() {
        val runtimes = ReminderRuntimes.of(program, listOf(item(1, "20:15"), item(2, "20:15", cinemaId = 1)), seedKeys)
        assertEquals(mapOf(1L to 104, 2L to 97), runtimes)
    }

    @Test
    fun anotherTimeDayOrCinemaGivesNone() {
        val visits = listOf(
            item(1, "20:00"),
            item(2, "20:15", date = "2026-10-16"),
            item(3, "20:15", cinemaId = 7), // a cinema without a seed key (user-added)
            item(4, null),
            item(5, "18:00", date = "2026-10-18"), // the film has no runtime
        )
        assertEquals(emptyMap<Long, Int>(), ReminderRuntimes.of(program, visits, seedKeys))
        assertEquals(emptyMap<Long, Int>(), ReminderRuntimes.of(null, listOf(item(1, "20:15")), seedKeys))
    }

    @Test
    fun severalShowingsAtThatTimeAreToldApartByTheFilm() {
        val visits = listOf(
            item(1, "22:30", film = "paper  kites"),
            item(2, "22:30", film = "The Quiet Projectionist"),
            item(3, "22:30"), // no title: they disagree
            item(4, "22:30", film = "Another Film"), // no film of that name: they disagree
        )
        assertEquals(mapOf(1L to 97, 2L to 104), ReminderRuntimes.of(program, visits, seedKeys))
    }
}
