package io.github.katznmeik.cinexperience.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Counts shown in Settings → Data (spec §6.7). */
data class DataCounts(
    val cinemas: Int,
    val halls: Int,
    val layoutsDrawn: Int,
    val visits: Int,
) {
    companion object {
        fun of(cinemas: List<CinemaDetail>) = DataCounts(
            cinemas = cinemas.size,
            halls = cinemas.sumOf { it.halls.size },
            layoutsDrawn = cinemas.sumOf { c -> c.halls.count { it.hall.isLayoutDrawn } },
            visits = cinemas.sumOf { it.scores.visitCount },
        )
    }
}

data class SettingsUiState(
    val appVersion: String,
    /** `null` while loading or if the counts couldn't be loaded. */
    val counts: DataCounts? = null,
    val countsError: Boolean = false,
)

/** Settings tab (spec §6.7). Backup actions follow in M6. */
class SettingsViewModel(cinemaRepository: CinemaRepository, appVersion: String) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = cinemaRepository.observeCinemaDetails()
        .map { SettingsUiState(appVersion, DataCounts.of(it)) }
        .catch { emit(SettingsUiState(appVersion, countsError = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appVersion))

    companion object {
        fun factory(cinemaRepository: CinemaRepository, appVersion: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(cinemaRepository, appVersion) } }
    }
}
