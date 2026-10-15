package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramActionResult
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class FakeProgramRepositoryTest {

    @Test
    fun theSampleProgramIsConsistent() {
        val program = SampleProgram.program()
        assertEquals(5, program.films.size)
        assertEquals(10, program.showings.size)
        assertTrue(program.showings.all { it.cinema.seedKey != null })
        assertEquals("blauer-stern", program.showings.single { it.cinema.name == "Blauer Stern" }.cinema.seedKey)
        assertEquals(FilmKind.SPECIAL, program.film(SampleProgram.MIDNIGHT_SURPRISE)!!.kind)
        // The series only lists showings of films.
        assertEquals(2, program.showingsOf(SampleProgram.AUTUMN_CLASSICS).size)
        assertTrue(program.showings.none { it.filmKey == SampleProgram.AUTUMN_CLASSICS })
    }

    @Test
    fun theBuilderNumbersAndSortsShowings() {
        val program = buildProgram {
            film("A Film") {
                showing("2026-10-15T17:15", "Odeon", ShowingVersion.OV)
                showing("2026-10-15T15:00", "Kino Unbekannt")
            }
        }
        assertEquals(listOf("1001-2", "1001-1"), program.showings.map { it.id })
        assertEquals("a-film", program.films.single().slug)
        assertNull(program.showings.first().cinema.seedKey)
    }

    @Test
    fun downloadsOnlyWhenAllowedAndWhenStale() = runTest {
        val repository = FakeProgramRepository()
        repository.refresh()
        repository.refreshIfStale()
        assertEquals(0, repository.downloads)
        assertNull(repository.state.value.program)

        assertEquals(ProgramActionResult.Success, repository.allowDownloads())
        repository.refreshIfStale()
        assertEquals(1, repository.downloads)
        assertNotNull(repository.state.value.program)

        repository.refreshIfStale() // fresh
        assertEquals(1, repository.downloads)
        repository.now = repository.now.plus(Duration.ofHours(6))
        repository.refreshIfStale()
        assertEquals(2, repository.downloads)
        // From the future (the clock was set back): stale too.
        repository.now = repository.now.minus(Duration.ofHours(1))
        repository.refreshIfStale()
        assertEquals(3, repository.downloads)

        assertEquals(ProgramActionResult.Success, repository.setDownloadsEnabled(false))
        repository.refresh()
        assertEquals(3, repository.downloads)
        assertEquals(DownloadPermission.OFF, repository.state.value.permission)
    }

    @Test
    fun concurrentRefreshesShareOneDownload() = runTest {
        val repository = FakeProgramRepository.withProgram(null)
        val gate = CompletableDeferred<Unit>().also { repository.gate = it }
        val callers = List(3) { launch { repository.refresh() } }
        runCurrent()
        assertEquals(ProgramStatus.Loading, repository.state.value.status)
        gate.complete(Unit)
        runCurrent()
        assertTrue(callers.all { it.isCompleted })
        assertEquals(1, repository.downloads)
        assertNotNull(repository.state.value.program)
    }

    @Test
    fun switchingOffOrDeletingDuringADownloadKeepsNothing() = runTest {
        for (stop in listOf<suspend (FakeProgramRepository) -> ProgramActionResult>({ it.setDownloadsEnabled(false) }, { it.deleteProgram() })) {
            val repository = FakeProgramRepository.withProgram(null)
            repository.gate = CompletableDeferred()
            val refresh = launch { repository.refresh() }
            runCurrent()
            assertEquals(ProgramActionResult.Success, stop(repository))
            repository.gate!!.complete(Unit)
            runCurrent()
            assertTrue(refresh.isCompleted)
            assertNull(repository.state.value.program)
            assertEquals(ProgramStatus.Idle, repository.state.value.status)
        }
    }

    @Test
    fun failuresKeepTheProgramAndCanBeDismissed() = runTest {
        val repository = FakeProgramRepository.withProgram()
        val program = repository.state.value.program
        repository.nextError = ProgramError.OFFLINE
        repository.refresh()
        assertEquals(ProgramStatus.Failed(ProgramError.OFFLINE), repository.state.value.status)
        assertEquals(program, repository.state.value.program)
        repository.dismissError()
        assertEquals(ProgramStatus.Idle, repository.state.value.status)

        // A fresh program: opening the tab again clears an old error.
        repository.refresh()
        repository.refreshIfStale()
        assertEquals(ProgramStatus.Idle, repository.state.value.status)
    }

    @Test
    fun commandsReportTheirResult() = runTest {
        val repository = FakeProgramRepository.withProgram()
        repository.nextActionFailure = ProgramError.DELETE_FAILED
        assertEquals(ProgramActionResult.Failure(ProgramError.DELETE_FAILED), repository.deleteProgram())
        assertNotNull(repository.state.value.program)
        assertEquals(ProgramActionResult.Success, repository.deleteProgram())
        assertNull(repository.state.value.program)
        repository.nextActionFailure = ProgramError.SETTING_NOT_SAVED
        assertEquals(ProgramActionResult.Failure(ProgramError.SETTING_NOT_SAVED), repository.setDownloadsEnabled(false))
        assertEquals(DownloadPermission.OFF, repository.state.value.permission)
    }
}
