package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FilmViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val program = FakeProgramRepository.withProgram()
    private val quietProjectionist = FilmId(FilmKind.FILM, "the-quiet-projectionist")

    private fun TestScope.viewModel(film: FilmId = quietProjectionist, now: Instant = SampleProgram.FETCHED_AT): FilmViewModel {
        val vm = FilmViewModel(film, program, FakeCinexRepository(SampleData.store()), Clock.fixed(now, ZoneOffset.UTC))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private val FilmViewModel.loaded: FilmContent.Loaded get() = uiState.value.content as FilmContent.Loaded

    @Test
    fun upcomingShowingsByCinemaAndDay() = runTest {
        val vm = viewModel()
        assertEquals("The Quiet Projectionist", vm.loaded.film.title)
        assertEquals(listOf("delphi LUX", "Kant Kino", "International"), vm.loaded.cinemas.map { it.cinemaName })
        val delphi = vm.loaded.cinemas.first()
        assertEquals(listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16)), delphi.days.map { it.date })
        assertEquals("Saal 1", vm.loaded.cinemas.last().days.single().showings.single().hallName)

        // In the evening, the afternoon showing is gone.
        val later = viewModel(now = Instant.parse("2026-10-15T15:00:00Z"))
        assertEquals(listOf(LocalDate.of(2026, 10, 16)), later.loaded.cinemas.first { it.cinemaName == "delphi LUX" }.days.map { it.date })
    }

    @Test
    fun openOnYorckUsesTheFilmOrSpecialPage() = runTest {
        val vm = viewModel()
        vm.openPage()
        assertEquals("https://www.yorck.de/en/films/the-quiet-projectionist", vm.uiState.value.pendingLink)
        vm.onLinkHandled(true)

        val special = viewModel(FilmId(FilmKind.SPECIAL, "midnight-surprise-screening"))
        special.openPage()
        assertEquals("https://www.yorck.de/en/specials/midnight-surprise-screening", special.uiState.value.pendingLink)
    }

    @Test
    fun bookAndLogVisit() = runTest {
        val vm = viewModel()
        val showing = vm.loaded.cinemas.first().days.first().showings.first()
        vm.book(showing.showingId)
        assertEquals("https://www.yorck.de/en/checkout/seats?sessionid=${showing.showingId}", vm.uiState.value.pendingLink)
        assertEquals(SampleData.DELPHI_LUX, vm.logVisit(showing.showingId)!!.cinemaId)
    }

    @Test
    fun aFilmOfAnotherKindOrWithoutProgramIsNotFound() = runTest {
        assertEquals(FilmContent.NotFound, viewModel(FilmId(FilmKind.SPECIAL, "the-quiet-projectionist")).uiState.value.content)
        program.state.value = ProgramState(permission = DownloadPermission.ALLOWED, isLoaded = true)
        val vm = viewModel()
        assertEquals(FilmContent.NotFound, vm.uiState.value.content)
        vm.openPage()
        assertNull(vm.uiState.value.pendingLink)
        assertTrue(program.refreshCalls.isEmpty())
    }
}
