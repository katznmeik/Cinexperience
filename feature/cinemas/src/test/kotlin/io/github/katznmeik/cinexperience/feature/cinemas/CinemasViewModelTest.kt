package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaFilter
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaSort
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.shownFor
import io.github.katznmeik.cinexperience.feature.cinemas.list.sortedFor
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

class CinemasViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun summary(name: String, score: Double?, visits: Int, favourite: Boolean = false) =
        CinemaSummary(Cinema(id = name.hashCode().toLong(), name = name, isFavourite = favourite), score, visits, hallCount = 1)

    // Input in name order, as the repository delivers it.
    private val list = listOf(
        summary("Babylon", 4.2, 5),
        summary("Capitol", null, 0),
        summary("delphi LUX", 4.6, 8),
        summary("International", 4.2, 3),
        summary("Passage", null, 2),
    )

    @Test
    fun scoreSortPutsHighestFirstAndUnratedLastKeepingNameOrderForTies() {
        assertEquals(
            listOf("delphi LUX", "Babylon", "International", "Capitol", "Passage"),
            list.sortedFor(CinemaSort.SCORE).map { it.cinema.name },
        )
    }

    @Test
    fun nameSortKeepsRepositoryOrder() {
        assertEquals(list, list.sortedFor(CinemaSort.NAME))
    }

    @Test
    fun mostVisitedSortsByVisitCount() {
        assertEquals(
            listOf("delphi LUX", "Babylon", "International", "Passage", "Capitol"),
            list.sortedFor(CinemaSort.MOST_VISITED).map { it.cinema.name },
        )
    }

    private fun TestScope.collect(vm: CinemasViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun defaultIsScoreSortAndSortCanBeChanged() = runTest {
        val vm = CinemasViewModel(FakeCinexRepository(SampleData.store()))
        collect(vm)

        var state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(CinemaSort.SCORE, state.sort)
        assertEquals(13, state.cinemas.size)
        assertEquals(SampleData.visits.size, state.totalVisits)
        val scores = state.cinemas.map { it.overallScore }
        val rated = scores.takeWhile { it != null }.map { it!! }
        assertEquals(rated.sortedDescending(), rated)
        assertEquals(List(scores.size - rated.size) { null }, scores.drop(rated.size))

        vm.setSort(CinemaSort.NAME)
        state = vm.uiState.value
        assertEquals(CinemaSort.NAME, state.sort)
        assertEquals("Babylon Kreuzberg", state.cinemas.first().cinema.name)
    }

    @Test
    fun listUpdatesWhenDataChanges() = runTest {
        val repository = FakeCinexRepository(FakeStore())
        val vm = CinemasViewModel(repository)
        collect(vm)
        assertEquals(0, vm.uiState.value.cinemas.size)

        repository.createCinema("Rollberg")
        assertEquals(listOf("Rollberg"), vm.uiState.value.cinemas.map { it.cinema.name })
    }

    @Test
    fun loadErrorBecomesMessage() = runTest {
        val repository = FakeCinexRepository(SampleData.store()).apply { observeFailure = IllegalStateException("db") }
        val vm = CinemasViewModel(repository)
        collect(vm)

        assertEquals(CinemasViewModel.LOAD_FAILED, vm.uiState.value.errorMessage)
        assertEquals(0, vm.uiState.value.cinemas.size)
        assertNull(CinemasViewModel(FakeCinexRepository()).uiState.value.errorMessage)
    }

    @Test
    fun favouritesFilterKeepsOnlyFavouritesInScoreOrder() {
        val withFavourites = listOf(
            summary("Babylon", 4.2, 5, favourite = true),
            summary("Capitol", null, 0, favourite = true),
            summary("delphi LUX", 4.6, 8, favourite = true),
            summary("International", 4.2, 3),
            summary("Passage", null, 2),
        )
        assertEquals(
            listOf("delphi LUX", "Babylon", "Capitol"),
            withFavourites.shownFor(CinemaFilter.FAVOURITES, CinemaSort.NAME).map { it.cinema.name },
        )
        // ALL is exactly the sort.
        CinemaSort.entries.forEach { sort ->
            assertEquals(withFavourites.sortedFor(sort), withFavourites.shownFor(CinemaFilter.ALL, sort))
        }
    }

    @Test
    fun favouritesChipFiltersAndSortChipsGoBackToAll() = runTest {
        val repository = FakeCinexRepository(SampleData.store())
        repository.setFavourite(SampleData.BABYLON, true)
        repository.setFavourite(SampleData.DELPHI_LUX, true)
        repository.setFavourite(SampleData.PASSAGE, true)
        val vm = CinemasViewModel(repository)
        collect(vm)
        vm.setSort(CinemaSort.NAME)

        vm.showFavourites()
        var state = vm.uiState.value
        assertEquals(CinemaFilter.FAVOURITES, state.filter)
        val all = state.allCinemas
        val expected = all.filter { it.cinema.isFavourite }.sortedFor(CinemaSort.SCORE)
        assertEquals(setOf(SampleData.BABYLON, SampleData.DELPHI_LUX, SampleData.PASSAGE), expected.map { it.cinema.id }.toSet())
        assertEquals(expected, state.cinemas)
        // The header still counts every cinema.
        assertEquals(13, state.venueCount)
        assertEquals(SampleData.visits.size, state.totalVisits)
        assertFalse(state.showsNoFavourites)

        // A cinema unmarked elsewhere disappears from the filtered list right away.
        repository.setFavourite(SampleData.PASSAGE, false)
        state = vm.uiState.value
        assertEquals(setOf(SampleData.BABYLON, SampleData.DELPHI_LUX), state.cinemas.map { it.cinema.id }.toSet())

        vm.setSort(CinemaSort.MOST_VISITED)
        state = vm.uiState.value
        assertEquals(CinemaFilter.ALL, state.filter)
        assertEquals(CinemaSort.MOST_VISITED, state.sort)
        assertEquals(13, state.cinemas.size)
    }

    @Test
    fun favouritesWithoutAnyFavouriteShowsEmptyState() = runTest {
        val vm = CinemasViewModel(FakeCinexRepository(SampleData.store()))
        collect(vm)
        assertFalse(vm.uiState.value.showsNoFavourites)

        vm.showFavourites()
        val state = vm.uiState.value
        assertTrue(state.cinemas.isEmpty())
        assertTrue(state.showsNoFavourites)
        assertEquals(13, state.venueCount)
    }

    @Test
    fun noCinemasAtAllIsNotTheFavouritesEmptyState() = runTest {
        val vm = CinemasViewModel(FakeCinexRepository(FakeStore()))
        collect(vm)
        vm.showFavourites()
        assertFalse(vm.uiState.value.showsNoFavourites)
    }
}
