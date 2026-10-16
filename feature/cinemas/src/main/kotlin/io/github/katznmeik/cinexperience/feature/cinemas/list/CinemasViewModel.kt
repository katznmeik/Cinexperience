package io.github.katznmeik.cinexperience.feature.cinemas.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.model.ScoreOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Sort modes of the cinema list (spec §6.1), in chip order. */
enum class CinemaSort(val label: String) {
    SCORE("Score"),
    NAME("Name"),
    MOST_VISITED("Most visited"),
}

/**
 * Which cinemas the list shows (spec §6.1). A filter, independent of [CinemaSort]:
 * - ALL: every cinema, in the chosen [CinemaSort].
 * - FAVOURITES: only the favourite cinemas, always in [CinemaSort.SCORE] order;
 *   the chosen sort is kept for when the list goes back to ALL.
 *
 * In the chip row, the "Favourites" chip stands for FAVOURITES and the three sort
 * chips for ALL with that sort, so exactly one chip is selected.
 */
enum class CinemaFilter {
    ALL,
    FAVOURITES,
}

data class CinemasUiState(
    val isLoading: Boolean = true,
    val sort: CinemaSort = CinemaSort.SCORE,
    /** The cinemas shown: filtered by [filter] and ordered. */
    val cinemas: List<CinemaSummary> = emptyList(),
    val errorMessage: String? = null,
    val filter: CinemaFilter = CinemaFilter.ALL,
    /** Every cinema, whatever the filter; the header counts come from these. */
    val allCinemas: List<CinemaSummary> = cinemas,
) {
    val venueCount: Int get() = allCinemas.size
    val totalVisits: Int get() = allCinemas.sumOf { it.visitCount }

    /** The "Favourites" chip is selected but no cinema is a favourite (yet). */
    val showsNoFavourites: Boolean
        get() = filter == CinemaFilter.FAVOURITES && !isLoading && errorMessage == null &&
            allCinemas.isNotEmpty() && cinemas.isEmpty()
}

/**
 * Sorts [this] for [sort]. Expects the repository's natural name order as input;
 * all sorts are stable, so ties keep that order.
 * - SCORE: highest overall score first, unrated cinemas last ([ScoreOrder], like
 *   the Rankings: mathematically equal scores tie).
 * - NAME: natural name order (as delivered by the repository).
 * - MOST_VISITED: most visits first.
 */
fun List<CinemaSummary>.sortedFor(sort: CinemaSort): List<CinemaSummary> = when (sort) {
    CinemaSort.SCORE -> sortedWith(compareBy(ScoreOrder) { it.overallScore })
    CinemaSort.NAME -> this
    CinemaSort.MOST_VISITED -> sortedByDescending { it.visitCount }
}

/**
 * What the list shows for [filter] and [sort] (same input as [sortedFor]):
 * ALL → [sortedFor] [sort]; FAVOURITES → only favourite cinemas in score order.
 */
fun List<CinemaSummary>.shownFor(filter: CinemaFilter, sort: CinemaSort): List<CinemaSummary> = when (filter) {
    CinemaFilter.ALL -> sortedFor(sort)
    CinemaFilter.FAVOURITES -> this.filter { it.cinema.isFavourite }.sortedFor(CinemaSort.SCORE)
}

/**
 * The cinema list (spec §6.1). Score order is the default (owner decision); the
 * chosen sort and filter live in this ViewModel only (not persisted), like before
 * the Favourites chip existed.
 */
class CinemasViewModel(cinemaRepository: CinemaRepository) : ViewModel() {

    /** The chip choice; one flow, so a chip changes sort and filter in one step. */
    private data class Choice(val sort: CinemaSort = CinemaSort.SCORE, val filter: CinemaFilter = CinemaFilter.ALL)

    private val choice = MutableStateFlow(Choice())

    private val cinemas = cinemaRepository.observeCinemas()
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }

    val uiState: StateFlow<CinemasUiState> = combine(cinemas, choice) { result, choice ->
        val all = result.getOrDefault(emptyList())
        CinemasUiState(
            isLoading = false,
            sort = choice.sort,
            cinemas = all.shownFor(choice.filter, choice.sort),
            errorMessage = if (result.isFailure) LOAD_FAILED else null,
            filter = choice.filter,
            allCinemas = all,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CinemasUiState())

    /** A sort chip: all cinemas in [sort] order (leaves the Favourites filter). */
    fun setSort(sort: CinemaSort) {
        choice.value = Choice(sort, CinemaFilter.ALL)
    }

    /** The "Favourites" chip: only favourite cinemas, in score order. */
    fun showFavourites() {
        choice.update { it.copy(filter = CinemaFilter.FAVOURITES) }
    }

    companion object {
        const val LOAD_FAILED = "Couldn't load the cinemas."

        fun factory(cinemaRepository: CinemaRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CinemasViewModel(cinemaRepository) }
        }
    }
}
