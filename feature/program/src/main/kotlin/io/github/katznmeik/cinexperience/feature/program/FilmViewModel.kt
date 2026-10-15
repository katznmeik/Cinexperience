package io.github.katznmeik.cinexperience.feature.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.program.Film
import io.github.katznmeik.cinexperience.core.program.ProgramLinks
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ProgramTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate

/** A cinema's upcoming showings of the film, by day. */
data class FilmCinemaGroup(
    val cinemaName: String,
    val cinema: AppCinema?,
    val days: List<ShowingDay>,
)

sealed interface FilmContent {
    data object Loading : FilmContent

    /** The film isn't in the downloaded program (any more). */
    data object NotFound : FilmContent

    /** @property cinemas upcoming showings by cinema (in order of their first showing) and day. */
    data class Loaded(val film: Film, val cinemas: List<FilmCinemaGroup>) : FilmContent
}

data class FilmUiState(
    val content: FilmContent = FilmContent.Loading,
    val today: LocalDate = LocalDate.MIN,
    val message: String? = null,
    val pendingLink: String? = null,
)

private data class FilmLocal(val message: String? = null, val pendingLink: String? = null)

/**
 * The film page (spec §6.9): the film's facts and all its upcoming showings by
 * cinema and day, with the showing actions and "Open on yorck.de". It only reads
 * the downloaded program; it never downloads.
 */
class FilmViewModel(
    private val filmId: FilmId,
    private val programRepository: ProgramRepository,
    cinemaRepository: CinemaRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val local = MutableStateFlow(FilmLocal())
    private var latestJoin = CinemaJoin.EMPTY

    private val cinemas = cinemaRepository.observeCinemaDetails()
        .map { CinemaJoin(it) }
        .catch { emit(CinemaJoin.EMPTY) }
        .onStart { emit(CinemaJoin.EMPTY) }

    val uiState: StateFlow<FilmUiState> =
        combine(programRepository.state, cinemas, local) { program, join, local ->
            latestJoin = join
            FilmUiState(
                content = content(program, join),
                today = ProgramTime.now(clock).toLocalDate(),
                message = local.message,
                pendingLink = local.pendingLink,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilmUiState())

    private fun content(state: ProgramState, join: CinemaJoin): FilmContent {
        if (!state.isLoaded) return FilmContent.Loading
        val program = state.program ?: return FilmContent.NotFound
        val film = ProgramLookup.film(program, filmId) ?: return FilmContent.NotFound
        val now = clock.instant()
        val showings = program.showings.filter { it.isUpcoming(now) && ProgramLookup.belongsTo(program, it, filmId) }
        val groups = showings.groupBy { join[it.cinema.seedKey]?.seedKey ?: "yorck:${it.cinema.name}" }
            .values
            .map { list ->
                val first = showingItem(program, list.first(), join)
                FilmCinemaGroup(first.cinemaName, first.cinema, groupByDay(program, list, join))
            }
        return FilmContent.Loaded(film, groups)
    }

    private val film: Film? get() = (uiState.value.content as? FilmContent.Loaded)?.film

    /** "Open on yorck.de": the film's (or special's) page. */
    fun openPage() {
        val link = film?.let(ProgramLinks::page)
        local.update { if (link != null) it.copy(pendingLink = link) else it.copy(message = ShowingActions.NO_LINK) }
    }

    fun book(showingId: String) {
        val link = ShowingActions.bookingLink(programRepository.state.value.program, showingId, clock.instant())
        local.update { if (link != null) it.copy(pendingLink = link) else it.copy(message = ShowingActions.NO_LINK) }
    }

    fun onLinkHandled(opened: Boolean) {
        local.update { it.copy(pendingLink = null, message = if (opened) it.message else ShowingActions.NO_BROWSER) }
    }

    fun logVisit(showingId: String): LogVisitRequest? {
        val request = ShowingActions.logVisit(programRepository.state.value.program, showingId, latestJoin)
        if (request == null) local.update { it.copy(message = ShowingActions.SHOWING_GONE) }
        return request
    }

    fun messageShown() = local.update { it.copy(message = null) }

    companion object {
        fun factory(
            filmId: FilmId,
            programRepository: ProgramRepository,
            cinemaRepository: CinemaRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FilmViewModel(filmId, programRepository, cinemaRepository) }
        }
    }
}
