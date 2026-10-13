package io.github.katznmeik.cinexperience.feature.cinemas.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Sort modes of the cinema list (spec §6.1), in chip order. */
enum class CinemaSort(val label: String) {
    SCORE("Score"),
    NAME("Name"),
    MOST_VISITED("Most visited"),
}

data class CinemasUiState(
    val isLoading: Boolean = true,
    val sort: CinemaSort = CinemaSort.NAME,
    val cinemas: List<CinemaSummary> = emptyList(),
    val errorMessage: String? = null,
) {
    val totalVisits: Int get() = cinemas.sumOf { it.visitCount }
}

/**
 * Sorts [this] for [sort]. Expects the repository's natural name order as input;
 * all sorts are stable, so ties keep that order.
 * - SCORE: highest overall score first, unrated cinemas last.
 * - NAME: natural name order (as delivered by the repository).
 * - MOST_VISITED: most visits first.
 */
fun List<CinemaSummary>.sortedFor(sort: CinemaSort): List<CinemaSummary> = when (sort) {
    CinemaSort.SCORE -> sortedWith(compareBy(nullsLast(reverseOrder())) { it.overallScore })
    CinemaSort.NAME -> this
    CinemaSort.MOST_VISITED -> sortedByDescending { it.visitCount }
}

/** The cinema list (spec §6.1). Name order is the default (spec); the choice is not persisted. */
class CinemasViewModel(cinemaRepository: CinemaRepository) : ViewModel() {

    private val sort = MutableStateFlow(CinemaSort.NAME)

    private val cinemas = cinemaRepository.observeCinemas()
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }

    val uiState: StateFlow<CinemasUiState> = combine(cinemas, sort) { result, sort ->
        CinemasUiState(
            isLoading = false,
            sort = sort,
            cinemas = result.getOrDefault(emptyList()).sortedFor(sort),
            errorMessage = if (result.isFailure) LOAD_FAILED else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CinemasUiState())

    fun setSort(sort: CinemaSort) {
        this.sort.value = sort
    }

    companion object {
        const val LOAD_FAILED = "Couldn't load the cinemas."

        fun factory(cinemaRepository: CinemaRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CinemasViewModel(cinemaRepository) }
        }
    }
}
