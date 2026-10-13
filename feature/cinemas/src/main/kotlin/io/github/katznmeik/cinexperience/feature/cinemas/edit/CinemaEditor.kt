package io.github.katznmeik.cinexperience.feature.cinemas.edit

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The add / edit / rename / delete dialog currently shown, if any. */
sealed interface EditDialog {
    /** Add ([cinemaId] `null`) or edit a cinema. [delete] is set for user-added cinemas only. */
    data class CinemaForm(
        val cinemaId: Long?,
        val name: String = "",
        val address: String = "",
        val notes: String = "",
        val nameError: String? = null,
        val delete: ConfirmDeleteCinema? = null,
    ) : EditDialog {
        val isNew: Boolean get() = cinemaId == null
    }

    /** Add ([hallId] `null`) or rename a hall of [cinemaId]. */
    data class HallName(
        val cinemaId: Long,
        val hallId: Long?,
        val name: String = "",
        val nameError: String? = null,
    ) : EditDialog {
        val isNew: Boolean get() = hallId == null
    }

    data class ConfirmDeleteCinema(
        val cinemaId: Long,
        val name: String,
        val hallCount: Int,
        val visitCount: Int,
    ) : EditDialog

    data class ConfirmDeleteHall(
        val hallId: Long,
        val name: String,
        val visitCount: Int,
    ) : EditDialog
}

/** User-facing texts of the editor; also used by the tests. */
internal object EditMessages {
    const val NAME_REQUIRED = "Enter a name"
    const val PRELOADED_CINEMA = "Preloaded cinemas can't be deleted, only edited."
    const val PRELOADED_HALL = "Preloaded halls can't be deleted, only renamed."
    const val SAVE_FAILED = "Couldn't save. Please try again."
    const val DELETE_FAILED = "Couldn't delete. Please try again."
    const val GONE = "This item no longer exists."

    fun deleted(name: String) = "Deleted $name"
}

/**
 * Dialog state and write actions shared by the cinema detail and the manage screen:
 * add / edit / delete cinemas, add / rename / delete halls (spec §6.2, §6.7).
 *
 * Names are validated here (blank → inline error); duplicate names are allowed.
 * Repository failures never crash: they close the dialog and set [message].
 */
internal class CinemaEditor(
    private val scope: CoroutineScope,
    private val cinemaRepository: CinemaRepository,
    private val hallRepository: HallRepository,
    private val onCinemaDeleted: (Long) -> Unit = {},
) {
    private val _dialog = MutableStateFlow<EditDialog?>(null)
    val dialog: StateFlow<EditDialog?> = _dialog.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** A one-off message for a snackbar; call [messageShown] once it was shown. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun startAddCinema() {
        _dialog.value = EditDialog.CinemaForm(cinemaId = null)
    }

    fun startEditCinema(detail: CinemaDetail) {
        val cinema = detail.cinema
        _dialog.value = EditDialog.CinemaForm(
            cinemaId = cinema.id,
            name = cinema.name,
            address = cinema.address.orEmpty(),
            notes = cinema.notes.orEmpty(),
            delete = if (cinema.isPreloaded) null else detail.deleteConfirmation(),
        )
    }

    fun startAddHall(cinemaId: Long) {
        _dialog.value = EditDialog.HallName(cinemaId = cinemaId, hallId = null)
    }

    fun startRenameHall(hall: Hall) {
        _dialog.value = EditDialog.HallName(cinemaId = hall.cinemaId, hallId = hall.id, name = hall.name)
    }

    fun requestDeleteCinema(detail: CinemaDetail) {
        if (detail.cinema.isPreloaded) {
            _dialog.value = null
            _message.value = EditMessages.PRELOADED_CINEMA
        } else {
            _dialog.value = detail.deleteConfirmation()
        }
    }

    /** "Delete cinema" inside the edit form of a user-added cinema: asks to confirm. */
    fun deleteFromForm() {
        (_dialog.value as? EditDialog.CinemaForm)?.delete?.let { _dialog.value = it }
    }

    fun requestDeleteHall(hall: HallSummary) {
        if (hall.hall.isPreloaded) {
            _dialog.value = null
            _message.value = EditMessages.PRELOADED_HALL
        } else {
            _dialog.value = EditDialog.ConfirmDeleteHall(hall.hall.id, hall.hall.name, hall.visitCount)
        }
    }

    fun onNameChange(name: String) {
        _dialog.value = when (val d = _dialog.value) {
            is EditDialog.CinemaForm -> d.copy(name = name, nameError = null)
            is EditDialog.HallName -> d.copy(name = name, nameError = null)
            else -> d
        }
    }

    fun onAddressChange(address: String) {
        (_dialog.value as? EditDialog.CinemaForm)?.let { _dialog.value = it.copy(address = address) }
    }

    fun onNotesChange(notes: String) {
        (_dialog.value as? EditDialog.CinemaForm)?.let { _dialog.value = it.copy(notes = notes) }
    }

    fun dismiss() {
        _dialog.value = null
    }

    fun messageShown() {
        _message.value = null
    }

    /** Saves the form or performs the confirmed delete. */
    fun confirm() {
        when (val d = _dialog.value) {
            null -> Unit
            is EditDialog.CinemaForm -> {
                if (d.name.isBlank()) {
                    _dialog.value = d.copy(nameError = EditMessages.NAME_REQUIRED)
                    return
                }
                runWrite(EditMessages.SAVE_FAILED) {
                    if (d.cinemaId == null) {
                        cinemaRepository.createCinema(d.name, d.address, d.notes)
                    } else if (!cinemaRepository.updateCinema(d.cinemaId, d.name, d.address, d.notes)) {
                        _message.value = EditMessages.GONE
                    }
                }
            }
            is EditDialog.HallName -> {
                if (d.name.isBlank()) {
                    _dialog.value = d.copy(nameError = EditMessages.NAME_REQUIRED)
                    return
                }
                runWrite(EditMessages.SAVE_FAILED) {
                    if (d.hallId == null) {
                        hallRepository.createHall(d.cinemaId, d.name)
                    } else if (!hallRepository.renameHall(d.hallId, d.name)) {
                        _message.value = EditMessages.GONE
                    }
                }
            }
            is EditDialog.ConfirmDeleteCinema -> runWrite(EditMessages.DELETE_FAILED) {
                val result = cinemaRepository.deleteCinema(d.cinemaId)
                _message.value = result.message(d.name, EditMessages.PRELOADED_CINEMA)
                if (result == DeleteResult.DELETED) onCinemaDeleted(d.cinemaId)
            }
            is EditDialog.ConfirmDeleteHall -> runWrite(EditMessages.DELETE_FAILED) {
                _message.value = hallRepository.deleteHall(d.hallId).message(d.name, EditMessages.PRELOADED_HALL)
            }
        }
    }

    /** Closes the dialog and runs [block]; any failure becomes [failureMessage]. */
    private fun runWrite(failureMessage: String, block: suspend () -> Unit) {
        _dialog.value = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = failureMessage
            }
        }
    }

    private fun DeleteResult.message(name: String, preloaded: String): String = when (this) {
        DeleteResult.DELETED -> EditMessages.deleted(name)
        DeleteResult.NOT_FOUND -> EditMessages.GONE
        DeleteResult.PRELOADED -> preloaded
    }

    private fun CinemaDetail.deleteConfirmation() =
        EditDialog.ConfirmDeleteCinema(cinema.id, cinema.name, halls.size, scores.visitCount)
}
