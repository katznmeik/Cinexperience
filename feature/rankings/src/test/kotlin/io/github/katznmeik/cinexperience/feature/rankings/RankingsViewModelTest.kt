package io.github.katznmeik.cinexperience.feature.rankings

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRankingsRepository
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class RankingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var nextVisitId = 1L

    private fun visit(hallId: Long, sound: Int? = null, picture: Int? = null, atmosphere: Int? = null) = Visit(
        id = nextVisitId++,
        hallId = hallId,
        date = LocalDate.of(2026, 9, 1),
        hallRatings = HallRatings(sound = sound?.let(::Rating), picture = picture?.let(::Rating)),
        cinemaRatings = CinemaRatings(atmosphere = atmosphere?.let(::Rating)),
    )

    private val cinemas = listOf(Cinema(1, "Alpha"), Cinema(2, "Beta"), Cinema(3, "Gamma"))
    private val halls = listOf(
        Hall(11, 1, "Saal 10"),
        Hall(12, 1, "Saal 2"),
        Hall(21, 2, "Saal 1"),
        Hall(31, 3, "Saal 1"),
    )

    private fun TestScope.viewModel(visits: List<Visit>): Pair<RankingsViewModel, FakeCinexRepository> {
        val repository = FakeCinexRepository(FakeStore(cinemas, halls, visits))
        val vm = RankingsViewModel(CinemaRankingsRepository(repository))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm to repository
    }

    private fun RankingsViewModel.rows() = uiState.value.items.map { Triple(it.position, it.title, it.subtitle) }

    @Test
    fun defaultsToHallsOverallHighestFirstUnratedLast() = runTest {
        val (vm, _) = viewModel(listOf(visit(11, sound = 3), visit(21, sound = 5), visit(12, sound = 4)))
        val state = vm.uiState.value
        assertEquals(RankingScope.HALLS, state.scope)
        assertEquals(HallRankingCategory.OVERALL, state.hallCategory)
        assertFalse(state.nothingRated)
        assertEquals(
            listOf(
                Triple(1, "Saal 1", "Beta"),
                Triple(2, "Saal 2", "Alpha"),
                Triple(3, "Saal 10", "Alpha"),
                Triple(null, "Saal 1", "Gamma"),
            ),
            vm.rows(),
        )
        assertNull(state.items.last().score)
    }

    @Test
    fun tiesGoToMoreRatingsThenNaturalNameOrder() = runTest {
        val (vm, _) = viewModel(
            listOf(
                visit(11, sound = 4), // Alpha Saal 10: 4.0 from one rating
                visit(12, sound = 4), // Alpha Saal 2: 4.0 from one rating
                visit(21, sound = 4), visit(21, sound = 4), // Beta Saal 1: 4.0 from two ratings
            ),
        )
        assertEquals(
            listOf(Triple(1, "Saal 1", "Beta"), Triple(2, "Saal 2", "Alpha"), Triple(3, "Saal 10", "Alpha")),
            vm.rows().take(3),
        )
    }

    @Test
    fun categorySwitchRanksBySingleCategory() = runTest {
        val (vm, _) = viewModel(listOf(visit(11, sound = 5, picture = 1), visit(12, sound = 2, picture = 5)))
        assertEquals(listOf("Saal 2", "Saal 10"), vm.rows().take(2).map { it.second }) // overall 3.5 vs 3.0
        vm.selectCategory(HallRankingCategory.SOUND.ordinal)
        assertEquals(HallRankingCategory.SOUND, vm.uiState.value.hallCategory)
        assertEquals(listOf("Saal 10", "Saal 2"), vm.rows().take(2).map { it.second })
        vm.selectCategory(HallRankingCategory.LEGROOM.ordinal)
        assertTrue(vm.uiState.value.items.all { it.score == null && it.position == null })
    }

    @Test
    fun cinemaScopeUsesCinemaScoresAndKeepsCategoriesPerScope() = runTest {
        val (vm, _) = viewModel(listOf(visit(11, sound = 2, atmosphere = 5), visit(21, sound = 4)))
        vm.selectCategory(HallRankingCategory.SOUND.ordinal)
        vm.setScope(RankingScope.CINEMAS)
        val state = vm.uiState.value
        assertEquals(CinemaRankingCategory.OVERALL, state.cinemaCategory)
        assertEquals(listOf("Beta", "Alpha", "Gamma"), state.items.map { it.title }) // 4.0, mean(5, 2) = 3.5, –
        assertEquals("2 halls · 1 visit", state.items[1].subtitle)
        assertEquals(listOf("Overall", "Atmosphere", "Bar / snacks", "Popcorn quality", "Seat comfort"), state.chips.map { it.label })

        vm.selectCategory(CinemaRankingCategory.ATMOSPHERE.ordinal)
        assertEquals(listOf(Triple(1, "Alpha", "2 halls · 1 visit")), vm.rows().take(1))
        assertNull(vm.uiState.value.items[1].position)

        vm.setScope(RankingScope.HALLS)
        assertEquals(HallRankingCategory.SOUND, vm.uiState.value.hallCategory)
    }

    @Test
    fun nothingRatedShowsEmptyState() = runTest {
        val (vm, _) = viewModel(listOf(visit(11)))
        assertTrue(vm.uiState.value.nothingRated)
    }

    @Test
    fun updatesWhenVisitsChange() = runTest {
        val (vm, repository) = viewModel(emptyList())
        assertTrue(vm.uiState.value.nothingRated)
        repository.saveVisit(visit(31, sound = 5).copy(id = 0))
        assertFalse(vm.uiState.value.nothingRated)
        assertEquals(Triple(1, "Saal 1", "Gamma"), vm.rows().first())
    }

    @Test
    fun loadFailureBecomesMessage() = runTest {
        val repository = FakeCinexRepository(FakeStore(cinemas, halls)).apply { observeFailure = IllegalStateException() }
        val vm = RankingsViewModel(CinemaRankingsRepository(repository))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(RankingsViewModel.LOAD_FAILED, vm.uiState.value.errorMessage)
    }
}
