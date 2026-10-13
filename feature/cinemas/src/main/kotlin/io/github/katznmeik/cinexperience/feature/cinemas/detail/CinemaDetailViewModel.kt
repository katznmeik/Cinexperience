package io.github.katznmeik.cinexperience.feature.cinemas.detail

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface CinemaDetailContent {
    data object Loading : CinemaDetailContent
    data object NotFound : CinemaDetailContent
    data class Error(val message: String) : CinemaDetailContent
    data class Loaded(val detail: CinemaDetail) : CinemaDetailContent
}

data class CinemaDetailUiState(
    val content: CinemaDetailContent = CinemaDetailContent.Loading,
    val dialog: EditDialog? = null,
    val message: String? = null,
    /** The cinema was deleted here; the screen should close. */
    val closed: Boolean = false,
)

/** Cinema detail (spec §6.2): header scores, halls, edit / add hall / delete actions. */
class CinemaDetailViewModel(
    private val cinemaId: Long,
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
) : ViewModel() {

    private val closed = MutableStateFlow(false)

    private val editor = CinemaEditor(viewModelScope, cinemaRepository, hallRepository) { closed.value = true }

    private val content = cinemaRepository.observeCinemaDetail(cinemaId)
        .map { detail -> detail?.let { CinemaDetailContent.Loaded(it) } ?: CinemaDetailContent.NotFound }
        .catch { emit(CinemaDetailContent.Error(LOAD_FAILED)) }

    val uiState: StateFlow<CinemaDetailUiState> =
        combine(content, editor.dialog, editor.message, closed) { content, dialog, message, closed ->
            CinemaDetailUiState(content, dialog, message, closed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CinemaDetailUiState())

    private val detail: CinemaDetail?
        get() = (uiState.value.content as? CinemaDetailContent.Loaded)?.detail

    fun editCinema() {
        detail?.let(editor::startEditCinema)
    }

    fun addHall() = editor.startAddHall(cinemaId)

    fun renameHall(hall: HallSummary) = editor.startRenameHall(hall.hall)

    fun deleteHall(hall: HallSummary) = editor.requestDeleteHall(hall)

    /** Asks to confirm deleting this cinema (only user-added ones can be deleted). */
    fun deleteCinema() {
        detail?.let(editor::requestDeleteCinema)
    }

    fun onNameChange(name: String) = editor.onNameChange(name)

    fun onAddressChange(address: String) = editor.onAddressChange(address)

    fun onNotesChange(notes: String) = editor.onNotesChange(notes)

    fun deleteFromForm() = editor.deleteFromForm()

    fun confirmDialog() = editor.confirm()

    fun dismissDialog() = editor.dismiss()

    fun messageShown() = editor.messageShown()

    companion object {
        const val LOAD_FAILED = "Couldn't load this cinema."

        fun factory(
            cinemaId: Long,
            cinemaRepository: CinemaRepository,
            hallRepository: HallRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CinemaDetailViewModel(cinemaId, cinemaRepository, hallRepository) }
        }
    }
}
