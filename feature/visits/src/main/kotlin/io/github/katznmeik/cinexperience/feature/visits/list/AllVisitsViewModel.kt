package io.github.katznmeik.cinexperience.feature.visits.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** @property visits newest first; empty while loading. */
data class AllVisitsUiState(
    val isLoading: Boolean = true,
    val visits: List<VisitListItem> = emptyList(),
    val errorMessage: String? = null,
)

/** All visits across all cinemas, newest first (spec §6.8). */
class AllVisitsViewModel(visitRepository: VisitRepository) : ViewModel() {

    val uiState: StateFlow<AllVisitsUiState> = visitRepository.observeAllVisits()
        .map { AllVisitsUiState(isLoading = false, visits = it) }
        .catch { emit(AllVisitsUiState(isLoading = false, errorMessage = LOAD_FAILED)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllVisitsUiState())

    companion object {
        const val LOAD_FAILED = "Couldn't load the visits."

        fun factory(visitRepository: VisitRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AllVisitsViewModel(visitRepository) }
        }
    }
}
