package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.time.LocalDate

/**
 * The open hall and cinema detail screens update while they are shown: an
 * observed flow emits again after overrides change or a visit is saved (M4).
 */
internal class LiveDetailTest : DatabaseTest() {

    private val date = LocalDate.of(2026, 10, 1)

    /** The running collectors; [stopCollectors] ends them before the database is closed. */
    private val collectors = mutableListOf<Job>()

    /** Collects [flow] until [stopCollectors]; the returned state holds its latest value. */
    private fun <T> TestScope.observe(flow: Flow<T>): StateFlow<T?> {
        val latest = MutableStateFlow<T?>(null)
        collectors += backgroundScope.launch(Dispatchers.Default) { flow.collect { latest.value = it } }
        return latest
    }

    /**
     * Cancels the collectors and waits until they have stopped. Without the join a
     * collector could still be re-querying when `@After` closes the database, and the
     * resulting error would be reported against the next test class.
     */
    private suspend fun stopCollectors() {
        collectors.forEach { it.cancelAndJoin() }
        collectors.clear()
    }

    private suspend fun <T> StateFlow<T?>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first { it != null && predicate(it) }!! } }

    @Test
    fun hallAndCinemaDetailFollowOverridesAndVisits() = runTest {
        try {
            followOverridesAndVisits()
        } finally {
            stopCollectors()
        }
    }

    private suspend fun TestScope.followOverridesAndVisits() {
        val cinemaId = cinemas.createCinema("Kino")
        val hallId = halls.createHall(cinemaId, "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(layout = layoutOf("SSSS")))
        val hall: StateFlow<HallDetail?> = observe(halls.observeHallDetail(hallId))
        val cinema: StateFlow<CinemaDetail?> = observe(cinemas.observeCinemaDetail(cinemaId))
        hall.awaitValue { it.overrides.isEmpty() }

        overrides.setSeatColour(hallId, SeatId(2), SeatColour.RED)
        hall.awaitValue { it.seats.getValue(SeatId(2)).colour == SeatColour.RED }

        overrides.setFavouriteSeat(hallId, SeatId(3))
        hall.awaitValue { it.favouriteSeat == SeatId(3) }
        cinema.awaitValue { it.halls.single().favouriteSeat == SeatLabel("1", 3) }

        // Moving the favourite updates both screens.
        overrides.setFavouriteSeat(hallId, SeatId(1))
        hall.awaitValue { it.favouriteSeat == SeatId(1) && !it.seats.getValue(SeatId(3)).isFavourite }
        cinema.awaitValue { it.halls.single().favouriteSeat == SeatLabel("1", 1) }

        // A new visit changes the seat colour from its rating and every score.
        visits.save(
            Visit(hallId = hallId, date = date, seatId = SeatId(4), seatRating = Rating(5), hallRatings = HallRatings(sound = Rating(4))),
        )
        hall.awaitValue { it.seats.getValue(SeatId(4)).colour == SeatColour.GREEN && it.scores.overall == 4.0 }
        cinema.awaitValue { it.halls.single().overallScore == 4.0 && it.scores.overall == 4.0 }

        overrides.setSeatColour(hallId, SeatId(2), null)
        hall.awaitValue { it.seats.getValue(SeatId(2)).colour == null }
        overrides.setFavouriteSeat(hallId, null)
        cinema.awaitValue { it.halls.single().favouriteSeat == null }
    }
}
