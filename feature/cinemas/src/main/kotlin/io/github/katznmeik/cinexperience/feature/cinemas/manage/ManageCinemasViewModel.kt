package io.github.katznmeik.cinexperience.feature.cinemas.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.feature.cinemas.edit.CinemaEditor
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ManageCinemasUiState(
    val isLoading: Boolean = true,
    val cinemas: List<CinemaDetail> = emptyList(),
    val errorMessage: String? = null,
    val dialog: EditDialog? = null,
    val message: String? = null,
) {
    val hallCount: Int get() = cinemas.sumOf { it.halls.size }
    val layoutsDrawn: Int get() = cinemas.sumOf { c -> c.halls.count { it.hall.isLayoutDrawn } }
}

/** Settings → Data → Manage cinemas & halls (spec §6.7). */
class ManageCinemasViewModel(
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
) : ViewModel() {

    private val editor = CinemaEditor(viewModelScope, cinemaRepository, hallRepository)

    private val cinemas = cinemaRepository.observeCinemaDetails()
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }

    val uiState: StateFlow<ManageCinemasUiState> =
        combine(cinemas, editor.dialog, editor.message) { result, dialog, message ->
            ManageCinemasUiState(
                isLoading = false,
                cinemas = result.getOrDefault(emptyList()),
                errorMessage = if (result.isFailure) LOAD_FAILED else null,
                dialog = dialog,
                message = message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageCinemasUiState())

    fun addCinema() = editor.startAddCinema()

    fun editCinema(cinema: CinemaDetail) = editor.startEditCinema(cinema)

    fun deleteCinema(cinema: CinemaDetail) = editor.requestDeleteCinema(cinema)

    fun addHall(cinemaId: Long) = editor.startAddHall(cinemaId)

    fun renameHall(hall: HallSummary) = editor.startRenameHall(hall.hall)

    fun deleteHall(hall: HallSummary) = editor.requestDeleteHall(hall)

    fun onNameChange(name: String) = editor.onNameChange(name)

    fun onAddressChange(address: String) = editor.onAddressChange(address)

    fun onNotesChange(notes: String) = editor.onNotesChange(notes)

    fun deleteFromForm() = editor.deleteFromForm { id -> uiState.value.cinemas.firstOrNull { it.cinema.id == id } }

    fun confirmDialog() = editor.confirm()

    fun dismissDialog() = editor.dismiss()

    fun messageShown() = editor.messageShown()

    companion object {
        const val LOAD_FAILED = "Couldn't load the cinemas."

        fun factory(cinemaRepository: CinemaRepository, hallRepository: HallRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { ManageCinemasViewModel(cinemaRepository, hallRepository) } }
    }
}
