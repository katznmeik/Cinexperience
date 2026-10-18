package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.data.repository.SaveVisitResult
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.model.seatOf
import io.github.katznmeik.cinexperience.core.ui.text.takeCodePoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Which visit the form edits. */
sealed interface VisitEditMode {
    /**
     * A new visit; [hallId] pre-selects the hall (opened from a hall, or the matched
     * hall of an imported ticket), `null` from the Cinemas tab. [prefill] fills cinema,
     * date, film and format ("Log visit" from a showing of the program, spec §11.1)
     * and, for an imported ticket (§6.10), the start time, the owner's seat (only with
     * [hallId]), the ticket draft and the notes; its cinema is used only without [hallId].
     */
    data class Add(val hallId: Long?, val prefill: VisitPrefillArgs? = null) : VisitEditMode

    /**
     * [importedBefore]: opened by the ticket import for this visit's ticket (spec §6.10);
     * the form says so once, and with [qrCodeNotStored] that the ticket's QR code couldn't
     * be stored again.
     */
    data class Edit(val visitId: Long, val importedBefore: Boolean = false, val qrCodeNotStored: Boolean = false) : VisitEditMode
}

/**
 * The add / edit visit form (spec §6.4). Only a hall is required; every rating is
 * optional and tapping the selected star or format again clears it.
 *
 * Seats belong to one hall, so changing the hall (or the cinema) clears the seat.
 *
 * Photos are copied into app storage as soon as they are picked or taken. On save
 * the replaced or removed photo is deleted; on discard the photos added in this
 * session are; deleting the visit deletes its photo. Files left behind (e.g. the
 * system closed the app mid-edit) are removed by the startup sweep.
 *
 * The form, the photos added in this session and a pending camera capture are kept
 * in [savedState], so the edit also survives process death (e.g. while the camera
 * app is in front). A restored form first drops the session photos whose files are
 * gone (see [checkRestoredPhotos]); the visit's saved photo is never touched.
 *
 * A stored visit with a ticket shows it in the Ticket section (spec §6.4). Save
 * never writes the stored ticket ([VisitRepository.saveVisit] keeps it and its QR
 * code); "Remove ticket" removes it at once after a confirmation, like "Delete
 * visit", because it can't be undone by discarding the form (the QR code only comes
 * back with the ticket PDF).
 *
 * An imported ticket (spec §6.10) is a [TicketDraft] until Save, kept in [savedState]
 * with the form: Save of a visit with a draft is the only
 * [TicketRepository.saveVisitWithTicket] call, which stores the visit and the ticket
 * together (without a QR code that [ShowingTime] says has expired for the form's date
 * and start time). Its booking number on another visit (imported meanwhile) leaves
 * the form for that visit ([VisitEditUiState.openImportedVisit]) and saves nothing.
 * "Remove ticket" drops a draft without asking; Back drops it with the form.
 *
 * @param ticketRepository the visits' tickets; `null` leaves out the Ticket section
 *   (only tests that don't deal with tickets pass `null`; the [factory] passes the app's).
 * @param clock "now" for the QR code of an imported ticket.
 */
class VisitEditViewModel(
    private val mode: VisitEditMode,
    private val cinemaRepository: CinemaRepository,
    private val visitRepository: VisitRepository,
    private val photoStore: VisitPhotoStore,
    private val savedState: SavedStateHandle,
    private val ticketRepository: TicketRepository? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VisitEditUiState(
            isEdit = mode is VisitEditMode.Edit,
            form = (mode as? VisitEditMode.Add).let { add ->
                val prefill = add?.prefill
                VisitForm(
                    hallId = add?.hallId,
                    // Only a date the form's date picker offers.
                    date = prefill?.date?.takeIf(VisitDates::isAllowed) ?: today(),
                    filmTitle = prefill?.filmTitle?.trim()?.takeCodePoints(MAX_FILM_LENGTH).orEmpty(),
                    format = prefill?.format,
                    startTime = prefill?.startTime?.takeIf { ShowingTime.isWholeMinute(it) },
                    // A seat id belongs to one hall: only with the hall it was matched in.
                    seatId = prefill?.seatId?.takeIf { add?.hallId != null },
                )
            },
            ticketDraft = (mode as? VisitEditMode.Add)?.prefill?.ticket,
            importNotes = (mode as? VisitEditMode.Add)?.prefill?.notes.orEmpty(),
        ),
    )
    val uiState: StateFlow<VisitEditUiState> = _uiState.asStateFlow()

    private val state: VisitEditUiState get() = _uiState.value

    /** The form as loaded or last saved; "unsaved changes" compares against it. */
    private var baseline: VisitForm = state.form

    /** The imported ticket the form was opened with (or `null` once stored); "unsaved changes" compares against it too. */
    private var baselineTicket: TicketDraft? = state.ticketDraft

    /** The booking's seat ids ([cachedBookingSeatIds]) were computed for these labels and seats. */
    private var bookingSeatsFor: Pair<LayoutLabels, List<TicketSeat>>? = null
    private var cachedBookingSeatIds: Set<SeatId> = emptySet()

    /** Id of the stored visit; 0 until a new visit is saved. */
    private var visitId: Long = (mode as? VisitEditMode.Edit)?.visitId ?: 0L

    /** The visit whose ticket [observeTicket] follows; 0 while none. */
    private var observedTicketVisitId: Long = 0L

    /** Photos copied into storage in this session; deleted unless saved. */
    private val sessionPhotos = LinkedHashSet<String>()

    /** The capture URI handed to the camera app, until its result arrives. */
    private var pendingCapture: String? = null

    private var visitLoaded = mode is VisitEditMode.Add
    private var cinemasLoaded = false

    /** The pre-filled cinema, applied once the cinemas have loaded (then `null`). */
    private var prefillCinemaId: Long? =
        (mode as? VisitEditMode.Add)?.takeIf { it.hallId == null }?.prefill?.cinemaId

    init {
        val draft = VisitDraft.decodeOrNull(savedState.get<String>(DRAFT_KEY))
        if (draft != null) {
            restore(draft)
        } else if (mode is VisitEditMode.Edit) {
            viewModelScope.launch { loadVisit(mode.visitId) }
        }
        viewModelScope.launch { observeCinemas() }
        observeStoredTicket()
        if (mode is VisitEditMode.Edit && mode.importedBefore && savedState.get<Boolean>(IMPORTED_BEFORE_SHOWN_KEY) != true) {
            // Once: not again after rotation or process death.
            savedState[IMPORTED_BEFORE_SHOWN_KEY] = true
            _uiState.update { it.copy(message = if (mode.qrCodeNotStored) "$IMPORTED_BEFORE $QR_CODE_NOT_STORED" else IMPORTED_BEFORE) }
        }
    }

    /** Starts [observeTicket] for the stored visit, once per visit; nothing before the visit is stored. */
    private fun observeStoredTicket() {
        val id = visitId
        if (id == 0L || id == observedTicketVisitId) return
        observedTicketVisitId = id
        viewModelScope.launch { observeTicket(id) }
    }

    /** Keeps [VisitEditUiState.ticket] up to date; a failure only leaves out the Ticket section. */
    private suspend fun observeTicket(id: Long) {
        val repository = ticketRepository ?: return
        try {
            repository.observeTicket(id).collect { ticket -> _uiState.update { it.copy(ticket = ticket).derived() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(ticket = null).derived() }
        }
    }

    private fun restore(draft: VisitDraft) {
        prefillCinemaId = null // the draft already holds it
        baseline = draft.baseline.toForm(fallbackDate = today())
        baselineTicket = draft.baselineTicket?.toDraft()
        visitId = draft.visitId
        sessionPhotos += draft.sessionPhotos
        pendingCapture = draft.pendingCapture
        _uiState.update {
            it.copy(
                form = draft.form.toForm(fallbackDate = today()),
                isEdit = draft.isEdit,
                isPhotoBusy = draft.pendingCapture != null,
                ticketDraft = draft.ticket?.toDraft(),
                importNotes = draft.importNotes,
            ).derived()
        }
        // The form stays loading (and is not written back) until its photos are checked.
        viewModelScope.launch { checkRestoredPhotos() }
    }

    /**
     * Drops the restored session photos whose files no longer exist (e.g. removed
     * while the app was gone), so save and discard leave them alone. If the form's
     * photo is one of them, the form loses it and says so. Only session photos are
     * checked: the photo of the stored visit belongs to the database.
     */
    private suspend fun checkRestoredPhotos() {
        val missing = try {
            sessionPhotos.toList().filterNot { photoStore.exists(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // can't tell: keep them, as before the check
        }
        sessionPhotos -= missing.toSet()
        val lost = state.form.photoPath?.takeIf { it in missing } != null
        visitLoaded = true
        _uiState.update {
            if (lost) it.copy(form = it.form.copy(photoPath = null), message = PHOTO_GONE).derived() else it.derived()
        }
        persist()
    }

    /** Keeps the edit in [savedState] (see the class comment). */
    private fun persist() {
        if (!visitLoaded) return
        savedState[DRAFT_KEY] = if (state.isLeaving) {
            null
        } else {
            VisitDraft(
                form = FormDraft.of(state.form),
                baseline = FormDraft.of(baseline),
                visitId = visitId,
                isEdit = state.isEdit,
                sessionPhotos = sessionPhotos.toList(),
                pendingCapture = pendingCapture,
                ticket = state.ticketDraft?.let(TicketDraftData::of),
                baselineTicket = baselineTicket?.let(TicketDraftData::of),
                importNotes = state.importNotes,
            ).encode()
        }
    }

    private suspend fun loadVisit(id: Long) {
        val visit = try {
            visitRepository.getVisit(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(status = VisitEditStatus.LoadFailed) }
            return
        }
        if (visit == null) {
            _uiState.update { it.copy(status = VisitEditStatus.NotFound) }
            return
        }
        val form = VisitForm.of(visit)
        baseline = form
        visitLoaded = true
        _uiState.update { it.copy(form = form).derived() }
        persist()
    }

    private suspend fun observeCinemas() {
        try {
            cinemaRepository.observeCinemaDetails().collect { details ->
                cinemasLoaded = true
                val options = details.map(CinemaDetail::toOption)
                // The pre-fill is taken out first, so a retried update can't apply it twice.
                val prefillId = prefillCinemaId.also { prefillCinemaId = null }
                _uiState.update { withPrefilledCinema(it.copy(cinemas = options), prefillId).derived() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                if (it.status == VisitEditStatus.Loading) it.copy(status = VisitEditStatus.LoadFailed) else it.copy(message = LOAD_FAILED)
            }
        }
    }

    /**
     * Applies the pre-filled cinema once (spec §11.1): its only hall, or the cinema
     * itself when it has several (the owner picks the hall). The pre-filled form
     * counts as unchanged, like a hall pre-selected from the hall page.
     */
    private fun withPrefilledCinema(current: VisitEditUiState, cinemaId: Long?): VisitEditUiState {
        cinemaId ?: return current
        val cinema = current.cinemas.firstOrNull { it.id == cinemaId } ?: return current
        val onlyHall = cinema.halls.singleOrNull()?.id
        val form = current.form.copy(hallId = onlyHall, cinemaId = if (onlyHall == null) cinema.id else null)
        if (baseline == current.form) baseline = form
        return current.copy(form = form)
    }

    // ---- Cinema, hall, date, film, format ----

    /** Choosing another cinema clears the hall and seat; a cinema with a single hall selects it. */
    fun selectCinema(cinemaId: Long) {
        val cinema = state.cinemas.firstOrNull { it.id == cinemaId } ?: return
        val current = state.form
        if (current.hallId != null && cinema.halls.any { it.id == current.hallId }) return
        if (current.hallId == null && current.cinemaId == cinemaId) return
        val onlyHall = cinema.halls.singleOrNull()?.id
        editForm(seatClearedMessage = current.seatId != null) {
            it.copy(hallId = onlyHall, cinemaId = if (onlyHall == null) cinemaId else null, seatId = null)
        }
    }

    /** Choosing another hall clears the seat: seat ids belong to one hall. */
    fun selectHall(hallId: Long) {
        val current = state.form
        if (current.hallId == hallId || state.cinemas.none { c -> c.halls.any { it.id == hallId } }) return
        editForm(seatClearedMessage = current.seatId != null) { it.copy(hallId = hallId, cinemaId = null, seatId = null) }
    }

    fun setDate(date: LocalDate) = editForm { it.copy(date = date) }

    /** The optional start time (Berlin clock time, to the minute); `null` clears it. */
    fun setStartTime(time: LocalTime?) = editForm { it.copy(startTime = time?.truncatedTo(ChronoUnit.MINUTES)) }

    fun setFilmTitle(text: String) = editForm { it.copy(filmTitle = text.takeCodePoints(MAX_FILM_LENGTH)) }

    fun setNote(text: String) = editForm { it.copy(note = text.takeCodePoints(MAX_NOTE_LENGTH)) }

    /** Selects [format], or clears it when it is already selected. */
    fun toggleFormat(format: ScreeningFormat) = editForm { it.copy(format = if (it.format == format) null else format) }

    // ---- Ratings: tapping the selected star clears the rating ----

    fun rateSeat(value: Int) = editForm { it.copy(seatRating = toggled(it.seatRating, value)) }

    fun rateHall(category: HallCategory, value: Int) =
        editForm { it.copy(hallRatings = it.hallRatings.withRating(category, toggled(it.hallRatings[category], value))) }

    fun rateCinema(category: CinemaCategory, value: Int) =
        editForm { it.copy(cinemaRatings = it.cinemaRatings.withRating(category, toggled(it.cinemaRatings[category], value))) }

    private fun toggled(current: Rating?, value: Int): Rating? = if (current?.value == value) null else Rating.ofOrNull(value)

    // ---- Seat picker ----

    fun openSeatPicker() {
        if (state.status != VisitEditStatus.Ready || state.seatPickerUnavailable != null) return
        _uiState.update { it.copy(seatPicker = SeatPickerState(it.form.seatId?.takeIf { _ -> !it.seatMissing })) }
    }

    /** A tap on the picker's map: selects the seat, or deselects it when tapped again. */
    fun tapPickerSeat(seatId: SeatId) {
        _uiState.update { s -> s.copy(seatPicker = s.seatPicker?.let { SeatPickerState(if (it.seatId == seatId) null else seatId) }) }
    }

    /** A seat chosen by row and number in the accessible picker. */
    fun choosePickerSeat(seatId: SeatId) {
        _uiState.update { s -> s.copy(seatPicker = s.seatPicker?.let { SeatPickerState(seatId) }) }
    }

    /** Takes the picker's seat into the form (no seat selected = no seat). */
    fun confirmSeat() {
        val picker = state.seatPicker ?: return
        _uiState.update { it.copy(seatPicker = null) }
        editForm { it.copy(seatId = picker.seatId) }
    }

    fun closeSeatPicker() {
        _uiState.update { it.copy(seatPicker = null) }
    }

    fun clearSeat() = editForm { it.copy(seatId = null) }

    // ---- Photo ----

    /** Result of the photo picker; `null` if the user cancelled. */
    fun onGalleryPhotoPicked(uri: String?) {
        if (uri == null || !canChangePhoto()) return
        _uiState.update { it.copy(isPhotoBusy = true) }
        viewModelScope.launch {
            importPhoto(uri)
            _uiState.update { it.copy(isPhotoBusy = false) }
        }
    }

    /** Prepares a capture file and asks the screen to start the camera app with it. */
    fun requestCamera() {
        if (!canChangePhoto() || pendingCapture != null) return
        _uiState.update { it.copy(isPhotoBusy = true) }
        viewModelScope.launch {
            try {
                val uri = photoStore.createCaptureUri()
                pendingCapture = uri
                _uiState.update { it.copy(cameraRequest = uri) }
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isPhotoBusy = false, message = CAMERA_FAILED) }
            }
        }
    }

    /** The screen has started the camera app with [VisitEditUiState.cameraRequest]. */
    fun onCameraLaunched() {
        _uiState.update { it.copy(cameraRequest = null) }
    }

    /** No camera app could be started. */
    fun onCameraUnavailable() {
        val capture = pendingCapture
        pendingCapture = null
        _uiState.update { it.copy(cameraRequest = null, isPhotoBusy = false, message = NO_CAMERA) }
        persist()
        if (capture != null) viewModelScope.launch { deleteCaptureQuietly(capture) }
    }

    /** Result of the camera app: [success] if it took a picture. */
    fun onCameraResult(success: Boolean) {
        val capture = pendingCapture ?: return
        pendingCapture = null
        viewModelScope.launch {
            if (success) importPhoto(capture)
            deleteCaptureQuietly(capture)
            _uiState.update { it.copy(isPhotoBusy = false, cameraRequest = null) }
            persist()
        }
    }

    /** Removes the photo from the visit (its file is deleted once the visit is saved). */
    fun removePhoto() {
        if (!canChangePhoto()) return
        editForm { it.copy(photoPath = null) }
    }

    private fun canChangePhoto(): Boolean = state.status == VisitEditStatus.Ready && !state.isSaving && !state.isLeaving

    private suspend fun importPhoto(uri: String) {
        try {
            val path = photoStore.importPhoto(uri)
            if (state.isLeaving) {
                deleteQuietly(path) // the form was discarded meanwhile
                return
            }
            sessionPhotos += path
            editForm { it.copy(photoPath = path) }
            persist()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _uiState.update { it.copy(message = PHOTO_FAILED) }
        }
    }

    // ---- Save, discard, delete ----

    /** The outcome of [save]'s write. */
    private sealed interface SaveOutcome {
        /** The visit was saved, with the imported ticket if [ticketStored]. */
        data class Saved(val result: SaveVisitResult, val ticketStored: Boolean = false) : SaveOutcome

        /** Nothing was saved: visit [visitId] has the imported ticket already. */
        data class BookingNumberInUse(val visitId: Long) : SaveOutcome
    }

    fun save() {
        if (!state.canSave) return
        val form = state.form
        val draft = state.ticketDraft
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val outcome = try {
                val repository = ticketRepository
                if (draft != null && repository != null) {
                    saveWithTicket(form, draft, repository)
                } else {
                    SaveOutcome.Saved(visitRepository.saveVisit(form.toVisit(visitId)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isSaving = false, message = SAVE_GONE) }
                return@launch
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, message = SAVE_FAILED) }
                return@launch
            }
            when (outcome) {
                is SaveOutcome.BookingNumberInUse -> leaveForImportedVisit(outcome.visitId)
                is SaveOutcome.Saved -> saved(form, outcome)
            }
        }
    }

    /**
     * Saves the visit with the imported ticket in one transaction (the only
     * [TicketRepository.saveVisitWithTicket] call): the draft's seats as printed (none
     * if they couldn't be read; the picked seat is the visit's, not the ticket's), and
     * its QR code unless it has expired for the form's date and start time.
     */
    private suspend fun saveWithTicket(form: VisitForm, draft: TicketDraft, repository: TicketRepository): SaveOutcome {
        val expired = ShowingTime.qrModulesExpired(form.date, form.startTime, clock.instant())
        val ticket = draft.toTicket(qrModules = draft.qrModules.takeUnless { expired })
        return when (val result = repository.saveVisitWithTicket(form.toVisit(visitId), ticket.copy(visitId = visitId))) {
            is SaveTicketResult.Saved -> SaveOutcome.Saved(SaveVisitResult(result.visitId, result.seatCleared), ticketStored = true)
            is SaveTicketResult.BookingNumberInUse -> SaveOutcome.BookingNumberInUse(result.visitId)
        }
    }

    /** Nothing was saved ([SaveOutcome.BookingNumberInUse]): the photos added here go, the form leaves for that visit. */
    private suspend fun leaveForImportedVisit(otherVisitId: Long) {
        _uiState.update { it.copy(isSaving = false, dialog = null, openImportedVisit = otherVisitId) }
        persist()
        pendingCapture?.let { capture ->
            pendingCapture = null
            deleteCaptureQuietly(capture)
        }
        deleteSessionPhotos(keep = null)
    }

    private suspend fun saved(form: VisitForm, outcome: SaveOutcome.Saved) {
        val result = outcome.result
        val kept = form.photoPath
        val replaced = baseline.photoPath?.takeIf { it != kept }
        deleteSessionPhotos(keep = kept)
        // The kept photo now belongs to the saved visit: a later discard must not delete it.
        sessionPhotos.clear()
        replaced?.let { deleteQuietly(it) }
        visitId = result.visitId
        if (outcome.ticketStored) {
            // Stored now: the Ticket section shows the ticket from the database from here on.
            baselineTicket = null
            _uiState.update { it.copy(ticketDraft = null) }
            observeStoredTicket()
        }
        when {
            result.seatCleared -> {
                // Saved without the seat: stay on the form so the user sees why.
                val saved = form.copy(seatId = null)
                baseline = saved
                _uiState.update {
                    it.copy(isSaving = false, isEdit = true, form = saved, message = SEAT_CLEARED_ON_SAVE).derived()
                }
            }
            else -> {
                baseline = form
                _uiState.update { it.copy(isSaving = false, isClosed = true).derived() }
            }
        }
        persist()
    }

    /** Close ("×" or Back): asks first if there are unsaved changes. Ignored while saving or removing the ticket. */
    fun requestClose() {
        if (state.isSaving || state.isLeaving || state.isRemovingTicket) return
        when {
            state.seatPicker != null -> closeSeatPicker()
            state.status == VisitEditStatus.Ready && state.hasUnsavedChanges ->
                _uiState.update { it.copy(dialog = VisitEditDialog.ConfirmDiscard) }
            else -> discard()
        }
    }

    /** Leaves without saving (an imported ticket not saved yet is dropped); photos added in this session are deleted. */
    fun discard() {
        if (state.isSaving || state.isLeaving || state.isRemovingTicket) return
        _uiState.update { it.copy(dialog = null, isClosed = true) }
        persist()
        pendingCapture?.let { capture ->
            pendingCapture = null
            viewModelScope.launch { deleteCaptureQuietly(capture) }
        }
        viewModelScope.launch { deleteSessionPhotos(keep = null) }
    }

    fun requestDelete() {
        if (visitId == 0L || state.isSaving || state.isLeaving || state.isRemovingTicket) return
        _uiState.update { it.copy(dialog = VisitEditDialog.ConfirmDelete) }
    }

    /** Deletes the visit and its photo, then closes. */
    fun confirmDelete() {
        if (visitId == 0L || state.isSaving || state.isLeaving) return
        _uiState.update { it.copy(dialog = null, isSaving = true) }
        viewModelScope.launch {
            try {
                visitRepository.deleteVisit(visitId) // false: already gone, which is fine too
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, message = DELETE_FAILED) }
                return@launch
            }
            deleteSessionPhotos(keep = null)
            baseline.photoPath?.let { deleteQuietly(it) }
            _uiState.update { it.copy(isSaving = false, isClosed = true) }
            persist()
        }
    }

    // ---- Ticket (spec §6.4) ----

    /**
     * The visit whose ticket "Show ticket" opens (spec §6.11); `null` without a
     * ticket and while the visit is being saved or deleted or the ticket removed.
     */
    fun ticketToShow(): Long? =
        state.ticket?.visitId?.takeUnless { state.isSaving || state.isLeaving || state.isRemovingTicket }

    /**
     * "Remove ticket": a stored ticket asks to confirm first; an imported one not saved
     * yet is dropped at once (nothing was stored, discarding the form brings nothing back).
     */
    fun requestRemoveTicket() {
        if (state.isSaving || state.isLeaving || state.isRemovingTicket) return
        when {
            state.ticketDraft != null -> {
                if (state.status != VisitEditStatus.Ready) return
                _uiState.update { it.copy(ticketDraft = null, message = TICKET_REMOVED).derived() }
                persist()
            }
            state.ticket != null -> _uiState.update { it.copy(dialog = VisitEditDialog.ConfirmRemoveTicket) }
        }
    }

    /**
     * Removes the visit's ticket with its QR code now (the visit and the form stay as
     * they are). Not tied to Save: the form never writes tickets. Closing the form
     * waits for it ([requestClose]), and the write runs to the end even if the form
     * goes away meanwhile, so a removal the owner confirmed is never half done.
     */
    fun confirmRemoveTicket() {
        val ticket = state.ticket
        val repository = ticketRepository
        if (ticket == null || repository == null || state.isSaving || state.isLeaving || state.isRemovingTicket) {
            _uiState.update { it.copy(dialog = null) }
            return
        }
        _uiState.update { it.copy(dialog = null, isRemovingTicket = true) }
        viewModelScope.launch {
            val removed = try {
                // false: already gone, which is fine too
                withContext(NonCancellable) { repository.removeTicket(ticket.visitId) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _uiState.update {
                if (removed) {
                    it.copy(isRemovingTicket = false, ticket = null, message = TICKET_REMOVED)
                } else {
                    it.copy(isRemovingTicket = false, message = REMOVE_TICKET_FAILED)
                }
            }
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialog = null) }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun deleteSessionPhotos(keep: String?) {
        val obsolete = sessionPhotos.filter { it != keep }
        sessionPhotos.retainAll(setOfNotNull(keep))
        persist()
        obsolete.forEach { deleteQuietly(it) }
    }

    private suspend fun deleteQuietly(path: String) {
        try {
            photoStore.delete(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left for the orphan sweep at the next app start.
        }
    }

    private suspend fun deleteCaptureQuietly(uri: String) {
        try {
            photoStore.deleteCapture(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left for the orphan sweep at the next app start.
        }
    }

    // ---- State helpers ----

    private fun editForm(seatClearedMessage: Boolean = false, transform: (VisitForm) -> VisitForm) {
        if (state.status != VisitEditStatus.Ready || state.isLeaving) return
        _uiState.update {
            val edited = it.copy(form = transform(it.form)).derived()
            if (seatClearedMessage && edited.form.seatId == null) edited.copy(message = SEAT_CLEARED) else edited
        }
        persist()
    }

    /** This state with everything derived from the form and the cinema list. */
    private fun VisitEditUiState.derived(): VisitEditUiState {
        var form = this.form
        val hall = form.hallId?.let { id -> cinemas.firstNotNullOfOrNull { c -> c.halls.firstOrNull { it.id == id } } }
        val ready = visitLoaded && cinemasLoaded
        if (ready && form.hallId != null && hall == null && mode is VisitEditMode.Add && visitId == 0L) {
            // Opened for a hall that no longer exists: start without a hall.
            form = form.copy(hallId = null, seatId = null)
            if (baseline.hallId != null) baseline = baseline.copy(hallId = null, seatId = null)
        }
        val cinemaId = hall?.cinemaId ?: form.cinemaId
        val cinema = cinemas.firstOrNull { it.id == cinemaId }
        val layout = hall?.layout
        val labels = if (layout != null && layout == this.layout && this.labels != null) this.labels else layout?.labels()
        val seatLabel = form.seatId?.let { labels?.labelOf(it) }
        val draft = ticketDraft
        return copy(
            status = if (status == VisitEditStatus.Loading && ready) VisitEditStatus.Ready else status,
            form = form,
            cinemaName = cinema?.name,
            hallName = hall?.name,
            halls = cinema?.halls.orEmpty(),
            layout = layout,
            labels = labels,
            screenWidth = hall?.screenWidth,
            screenDistance = hall?.screenDistance,
            screenOffset = hall?.screenOffset ?: 0f,
            seatLabel = seatLabel,
            seatMissing = form.seatId != null && seatLabel == null,
            seatPickerUnavailable = when {
                hall == null -> NO_HALL_FOR_SEAT
                hall.layoutUnreadable -> LAYOUT_UNREADABLE
                layout == null -> NO_LAYOUT
                labels?.seats.isNullOrEmpty() -> NO_SEATS
                else -> null
            },
            seatPicker = seatPicker?.takeIf { layout != null },
            layoutUnreadable = hall?.layoutUnreadable == true,
            hasUnsavedChanges = form.normalized() != baseline.normalized() || draft != baselineTicket,
            bookingSeatIds = bookingSeatIds(labels, draft?.seats ?: ticket?.seats.orEmpty()),
            draftQrCode = draft?.let {
                when {
                    it.qrModules == null -> DraftQrCode.NONE
                    ShowingTime.qrModulesExpired(form.date, form.startTime, clock.instant()) -> DraftQrCode.EXPIRED
                    else -> DraftQrCode.KEPT
                }
            },
        )
    }

    /** The ids of the booking's [seats] in [labels] (those that match, spec §11.2); cached per labels and seats. */
    private fun bookingSeatIds(labels: LayoutLabels?, seats: List<TicketSeat>): Set<SeatId> {
        if (labels == null || seats.isEmpty()) return emptySet()
        if (bookingSeatsFor?.let { it.first === labels && it.second == seats } != true) {
            bookingSeatsFor = labels to seats
            cachedBookingSeatIds = seats.mapNotNullTo(LinkedHashSet()) { labels.seatOf(it) }
        }
        return cachedBookingSeatIds
    }

    companion object {
        /** Key of the [VisitDraft] in the saved state. */
        internal const val DRAFT_KEY = "visitEdit.draft"

        /** Saved-state key: "This ticket was imported before" was shown ([VisitEditMode.Edit.importedBefore]). */
        internal const val IMPORTED_BEFORE_SHOWN_KEY = "visitEdit.importedBeforeShown"

        /** Limits in Unicode code points (an emoji counts once; a surrogate pair is never split). */
        const val MAX_FILM_LENGTH = 200
        const val MAX_NOTE_LENGTH = 4000

        const val LOAD_FAILED = "Couldn't load the visit."
        const val SAVE_FAILED = "Couldn't save the visit. Please try again."
        const val SAVE_GONE = "Couldn't save: the hall or this visit no longer exists."
        const val DELETE_FAILED = "Couldn't delete the visit. Please try again."
        const val SEAT_CLEARED = "Seat removed: seats belong to one hall."
        const val SEAT_CLEARED_ON_SAVE =
            "Saved without the seat: it is no longer in this hall's layout."
        const val PHOTO_FAILED = "Couldn't add that photo."
        const val PHOTO_GONE = "The photo is no longer available."
        const val CAMERA_FAILED = "Couldn't prepare the camera."
        const val NO_CAMERA = "No camera app found."
        const val NO_HALL_FOR_SEAT = "Choose a hall to pick your seat."
        const val NO_LAYOUT = "This hall has no seat layout yet. Draw it on the hall's page to pick seats."
        const val LAYOUT_UNREADABLE = "This hall's layout can't be shown by this version of the app."
        const val NO_SEATS = "This hall's layout has no seats yet."
        const val TICKET_REMOVED = "Ticket removed."
        const val REMOVE_TICKET_FAILED = "Couldn't remove the ticket. Please try again."
        const val IMPORTED_BEFORE = "This ticket was imported before."
        const val QR_CODE_NOT_STORED = "Couldn't store the ticket's QR code again."

        fun factory(
            mode: VisitEditMode,
            cinemaRepository: CinemaRepository,
            visitRepository: VisitRepository,
            ticketRepository: TicketRepository,
            photoStore: VisitPhotoStore,
            clock: Clock = Clock.systemDefaultZone(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VisitEditViewModel(
                    mode, cinemaRepository, visitRepository, photoStore, createSavedStateHandle(),
                    ticketRepository = ticketRepository,
                    clock = clock,
                )
            }
        }
    }
}

private fun CinemaDetail.toOption(): CinemaOption = CinemaOption(cinema.id, cinema.name, halls.map { it.hall })
