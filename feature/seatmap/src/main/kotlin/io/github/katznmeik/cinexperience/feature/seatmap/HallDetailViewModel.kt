package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface HallDetailUiState {
    data object Loading : HallDetailUiState
    data object NotFound : HallDetailUiState
    data class Error(val message: String) : HallDetailUiState
    data class Loaded(val hallName: String, val cinemaName: String) : HallDetailUiState
}

/** Hall detail (spec §6.3). For now only the names; the seat map follows in M4. */
class HallDetailViewModel(hallId: Long, hallRepository: HallRepository) : ViewModel() {

    val uiState: StateFlow<HallDetailUiState> = hallRepository.observeHallDetail(hallId)
        .map { detail ->
            detail?.let { HallDetailUiState.Loaded(it.hall.name, it.cinema.name) } ?: HallDetailUiState.NotFound
        }
        .catch { emit(HallDetailUiState.Error(LOAD_FAILED)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HallDetailUiState.Loading)

    companion object {
        const val LOAD_FAILED = "Couldn't load this hall."

        fun factory(hallId: Long, hallRepository: HallRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HallDetailViewModel(hallId, hallRepository) }
        }
    }
}
