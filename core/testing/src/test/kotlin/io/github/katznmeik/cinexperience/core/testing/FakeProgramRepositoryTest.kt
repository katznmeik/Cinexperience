package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
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
        assertEquals(4, program.films.size)
        assertEquals(10, program.showings.size)
        assertTrue(program.showings.zipWithNext().all { (a, b) -> a.start <= b.start })
        assertTrue(program.showings.all { it.cinema.seedKey != null })
        assertEquals("blauer-stern", program.showings.single { it.cinema.name == "Blauer Stern" }.cinema.seedKey)
        assertEquals(FilmKind.SPECIAL, program.film("midnight-surprise-screening")!!.kind)
    }

    @Test
    fun theBuilderNumbersShowings() {
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
    fun downloadsOnlyWhenAllowed() = runTest {
        val repository = FakeProgramRepository()
        repository.refresh()
        repository.refreshIfStale()
        assertEquals(0, repository.downloads)
        assertNull(repository.state.value.program)

        repository.allowDownloads()
        repository.refreshIfStale()
        assertEquals(1, repository.downloads)
        assertNotNull(repository.state.value.program)

        repository.refreshIfStale() // fresh
        assertEquals(1, repository.downloads)
        repository.now = repository.now.plus(Duration.ofHours(6))
        repository.refreshIfStale()
        assertEquals(2, repository.downloads)

        repository.setDownloadsEnabled(false)
        repository.refresh()
        assertEquals(2, repository.downloads)
        assertEquals(DownloadPermission.OFF, repository.state.value.permission)
    }

    @Test
    fun aFailureKeepsTheProgram() = runTest {
        val repository = FakeProgramRepository.withProgram()
        val program = repository.state.value.program
        repository.nextError = ProgramError.OFFLINE
        repository.refresh()
        assertEquals(ProgramStatus.Failed(ProgramError.OFFLINE), repository.state.value.status)
        assertEquals(program, repository.state.value.program)
    }
}
