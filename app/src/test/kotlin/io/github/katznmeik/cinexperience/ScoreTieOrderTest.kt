package io.github.katznmeik.cinexperience

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRankingsRepository
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaSort
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasViewModel
import io.github.katznmeik.cinexperience.feature.rankings.RankingScope
import io.github.katznmeik.cinexperience.feature.rankings.RankingsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The cinema list's score sort (spec §6.1) and the Rankings (spec §6.6) order two
 * cinemas with mathematically equal scores the same way: floating-point noise
 * decides neither (both use `ScoreOrder` from core:model).
 */
class ScoreTieOrderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun ratings(vararg values: Int): HallRatings = HallRatings(
        sound = Rating(values[0]),
        picture = Rating(values[1]),
        screenSize = Rating(values[2]),
        seatComfort = Rating(values[3]),
        legroom = Rating(values[4]),
    )

    /**
     * Alpha: atmosphere 2, bar 2 and a hall at 13/5 = 2.6, so mean[2.0, 2.0, 2.6] =
     * 2.1999999999999997. Beta: only a hall at 11/5 = 2.2. One rated visit each.
     */
    private val repository = FakeCinexRepository(
        FakeStore(
            cinemas = listOf(Cinema(1, "Alpha"), Cinema(2, "Beta")),
            halls = listOf(Hall(11, 1, "Saal 1"), Hall(21, 2, "Saal 1")),
            visits = listOf(
                Visit(
                    id = 1,
                    hallId = 11,
                    date = LocalDate.of(2026, 10, 1),
                    hallRatings = ratings(3, 3, 3, 2, 2),
                    cinemaRatings = CinemaRatings(atmosphere = Rating(2), barSnacks = Rating(2)),
                ),
                Visit(id = 2, hallId = 21, date = LocalDate.of(2026, 10, 2), hallRatings = ratings(3, 3, 2, 2, 1)),
            ),
        ),
    )

    @Test
    fun mathematicallyEqualScoresSortTheSameWayInTheCinemaListAndTheRankings() = runTest {
        val list = CinemasViewModel(repository)
        val rankings = RankingsViewModel(CinemaRankingsRepository(repository, repository))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rankings.uiState.collect {} }
        list.setSort(CinemaSort.SCORE)
        rankings.setScope(RankingScope.CINEMAS)

        // The scores differ only by noise: both read 2.2.
        val scores = list.uiState.value.cinemas.associate { it.cinema.name to it.overallScore!! }
        assertNotEquals(scores.getValue("Alpha"), scores.getValue("Beta"), 0.0)
        assertEquals(listOf("2.2", "2.2"), scores.values.map(::formatScore))

        // A tie in both places, broken by name (the Rankings first by rated visits: one each).
        val expected = listOf("Alpha", "Beta")
        assertEquals(expected, list.uiState.value.cinemas.map { it.cinema.name })
        assertEquals(expected, rankings.uiState.value.items.map { it.title })
        assertEquals(listOf(1, 2), rankings.uiState.value.items.map { it.position })

        // The Favourites filter sorts by score the same way.
        repository.setFavourite(1, true)
        repository.setFavourite(2, true)
        list.showFavourites()
        assertEquals(expected, list.uiState.value.cinemas.map { it.cinema.name })
    }
}
