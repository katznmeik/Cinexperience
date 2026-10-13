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
import io.github.katznmeik.cinexperience.feature.cinemas.edit.CinemaDeleteListener
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
    /**
     * The cinema was deleted here: the screen should close and show this message
     * on the screen it returns to.
     */
    val deletedMessage: String? = null,
)

/** Where a delete started on this screen stands. */
private sealed interface OwnDelete {
    data object None : OwnDelete
    data object Running : OwnDelete
    data class Done(val message: String) : OwnDelete
}

/** Cinema detail (spec §6.2): header scores, halls, edit / add hall / delete actions. */
class CinemaDetailViewModel(
    private val cinemaId: Long,
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
) : ViewModel() {

    private val ownDelete = MutableStateFlow<OwnDelete>(OwnDelete.None)

    private val editor = CinemaEditor(
        viewModelScope,
        cinemaRepository,
        hallRepository,
        deleteListener = object : CinemaDeleteListener {
            override fun onDeleting(cinemaId: Long) {
                ownDelete.value = OwnDelete.Running
            }

            override fun onDeleted(cinemaId: Long, message: String) {
                ownDelete.value = OwnDelete.Done(message)
            }

            override fun onDeleteFailed(cinemaId: Long) {
                ownDelete.value = OwnDelete.None
            }
        },
    )

    private val content = cinemaRepository.observeCinemaDetail(cinemaId)
        .map { detail -> detail?.let { CinemaDetailContent.Loaded(it) } ?: CinemaDetailContent.NotFound }
        .catch { emit(CinemaDetailContent.Error(LOAD_FAILED)) }

    /** Last loaded content, kept on screen while a delete started here closes it. */
    private var lastLoaded: CinemaDetailContent.Loaded? = null

    val uiState: StateFlow<CinemaDetailUiState> =
        combine(content, editor.dialog, editor.message, ownDelete) { content, dialog, message, ownDelete ->
            if (content is CinemaDetailContent.Loaded) lastLoaded = content
            val shown = if (ownDelete != OwnDelete.None && content !is CinemaDetailContent.Loaded) {
                lastLoaded ?: content
            } else {
                content
            }
            CinemaDetailUiState(shown, dialog, message, (ownDelete as? OwnDelete.Done)?.message)
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

    fun deleteFromForm() = editor.deleteFromForm { id -> detail?.takeIf { it.cinema.id == id } }

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
