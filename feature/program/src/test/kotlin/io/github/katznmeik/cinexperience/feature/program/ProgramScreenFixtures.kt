package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.FilmKey
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalTime

/** Program tab and film page states built from [SampleProgram] and [SampleData] (INVENTED data). */
internal object ProgramScreenFixtures {
    val today: LocalDate = LocalDate.of(2026, 10, 15)
    val now = SampleProgram.FETCHED_AT
    val program: Program = SampleProgram.program()

    /** The sample cinemas with delphi LUX and Kant Kino as favourites. */
    val join: CinemaJoin = runBlocking {
        val repository = FakeCinexRepository(SampleData.store())
        repository.setFavourite(SampleData.DELPHI_LUX, true)
        repository.setFavourite(8L, true) // Kant Kino
        CinemaJoin(repository.observeCinemaDetails().first())
    }

    fun state(
        filter: ProgramFilter = ProgramFilter(),
        program: Program? = this.program,
        expanded: Boolean = false,
        error: ProgramError? = null,
        refreshing: Boolean = false,
        mode: ProgramMode = ProgramMode.PROGRAM,
    ): ProgramUiState = ProgramUiState(
        mode = mode,
        today = today,
        asOf = program?.fetchedAt,
        hasProgram = program != null,
        isRefreshing = refreshing,
        errorMessage = error?.message,
        filter = filter,
        cinemaOptions = join.cinemas,
        eventOptions = ProgramFilters.eventLabels(program, now),
        dateOptions = ProgramFilters.datesBetween(today, today.plusDays(13)).toList(),
        lastDate = program?.showings?.last()?.start?.toLocalDate(),
        days = program?.let { groupByDay(it, ProgramFilters.apply(it, filter, now, today), join) }.orEmpty(),
        filtersExpanded = expanded,
    )

    val busyFilter = ProgramFilter(
        cinemas = setOf("delphi-lux", "kant-kino", "babylon-kreuzberg"),
        day = DayChoice.THIS_WEEK,
        from = LocalTime.of(16, 0),
        to = LocalTime.of(22, 0),
        versions = setOf(ShowingVersion.OMU, ShowingVersion.OV),
        events = setOf("Premiere"),
    )

    fun film(id: FilmKey = FilmKey(FilmKind.FILM, "the-quiet-projectionist")): FilmUiState {
        val film = ProgramLookup.film(program, id)!!
        val groups = filmGroups(program, film, now, join)
        return FilmUiState(content = FilmContent.Loaded(film, groups), today = today)
    }
}
