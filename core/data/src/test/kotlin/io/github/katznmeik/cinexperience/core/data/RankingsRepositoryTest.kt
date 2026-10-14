package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRankingsRepository
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

internal class RankingsRepositoryTest : DatabaseTest() {

    private val date = LocalDate.of(2026, 10, 1)

    @Test
    fun emitsEveryHallAndCinemaWithScores() = runTest {
        val rankings = CinemaRankingsRepository(cinemas, visits)
        val kino = cinemas.createCinema("Kino")
        val empty = cinemas.createCinema("Empty")
        val saal2 = halls.createHall(kino, "Saal 2")
        val saal10 = halls.createHall(kino, "Saal 10")
        visits.save(
            Visit(
                hallId = saal2, date = date,
                hallRatings = HallRatings(sound = Rating(4), picture = Rating(2)),
                cinemaRatings = CinemaRatings(atmosphere = Rating(5)),
            ),
        )
        visits.save(Visit(hallId = saal2, date = date, hallRatings = HallRatings(sound = Rating(5))))
        visits.save(Visit(hallId = saal10, date = date, seatRating = Rating(3))) // no hall/cinema rating
        visits.save(Visit(hallId = saal10, date = date, cinemaRatings = CinemaRatings(popcorn = Rating(3))))

        val result = rankings.observeRankings().first()

        assertEquals(listOf("Saal 2" to "Kino", "Saal 10" to "Kino"), result.halls.map { it.hall.name to it.cinemaName })
        val s2 = result.halls.first { it.hall.id == saal2 }.scores
        assertEquals(4.5, s2[HallCategory.SOUND]!!.average, 1e-9)
        assertEquals(2, s2[HallCategory.SOUND]!!.count)
        assertEquals(3.25, s2.overall!!, 1e-9)
        assertEquals(2, s2.visitCount)
        assertNull(result.halls.first { it.hall.id == saal10 }.scores.overall)

        assertEquals(listOf("Empty", "Kino"), result.cinemas.map { it.cinema.name })
        val k = result.cinemas.first { it.cinema.id == kino }
        assertEquals(2, k.hallCount)
        assertEquals(5.0, k.scores[CinemaCategory.ATMOSPHERE]!!.average, 1e-9)
        assertEquals((5.0 + 3.0 + 3.25) / 3, k.scores.overall!!, 1e-9)
        assertEquals(2, result.halls.first { it.hall.id == saal2 }.ratedVisitCount)
        assertEquals(0, result.halls.first { it.hall.id == saal10 }.ratedVisitCount)
        assertEquals(3, k.ratedVisitCount) // the seat-only visit doesn't count
        val e = result.cinemas.first { it.cinema.id == empty }
        assertEquals(0, e.hallCount)
        assertNull(e.scores.overall)
    }
}
