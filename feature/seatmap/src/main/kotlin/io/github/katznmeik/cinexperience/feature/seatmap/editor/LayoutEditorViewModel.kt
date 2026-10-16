package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.SeatIdsExhaustedException
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.model.seatsWithData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface EditorStatus {
    data object Loading : EditorStatus
    data object NotFound : EditorStatus
    data object LoadFailed : EditorStatus
    data object Ready : EditorStatus
}

sealed interface EditorDialog {
    /** Leaving with unsaved changes. */
    data object ConfirmDiscard : EditorDialog

    /**
     * Saving would remove seats that have visits or overrides (spec §6.5), listed
     * by their labels in the stored layout, and/or replace a layout this app
     * version can't read. The seats of an unreadable layout have no labels; with
     * [unreadableSeatsWithData] > 0 that many of them have visits or overrides,
     * and replacing the layout removes them all.
     */
    data class ConfirmSave(
        val removedSeats: List<SeatLabel>,
        val replacesUnreadableLayout: Boolean,
        val unreadableSeatsWithData: Int = 0,
    ) : EditorDialog

    /** Saving failed; with [canReload] the editor can start over from the stored layout. */
    data class SaveFailed(val message: String, val canReload: Boolean) : EditorDialog
}

data class LayoutEditorUiState(
    val status: EditorStatus = EditorStatus.Loading,
    val hallName: String = "",
    val draft: LayoutDraft? = null,
    val labels: LayoutLabels? = null,
    /** The paint tool; SEAT paints seats of [seatKind]. */
    val tool: CellType = CellType.SEAT,
    val seatKind: SeatKind = SeatKind.STANDARD,
    val selectedRow: Int? = null,
    val row: RowSummary? = null,
    /**
     * The screen is selected (spec §6.5): drawn green with its handles, and the panel
     * under the canvas shows its controls instead of the row panel. Never together
     * with a selected row.
     */
    val screenSelected: Boolean = false,
    /** Text of the row label field (may differ from the stored label while typing). */
    val labelInput: String = "",
    /**
     * Text of the block start fields, one per block of the selected row, indexed
     * like [RowSummary.blocks] (from the left, whatever the numbering direction).
     */
    val blockStartInputs: List<String> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    /** Canvas gestures align the reference screenshot instead of painting. */
    val referenceMode: Boolean = false,
    val referenceOpacity: Float = DEFAULT_REFERENCE_OPACITY,
    /** `null` = default placement (fitted to the grid). */
    val referenceAlignment: ReferenceAlignment? = null,
    val isImportingReference: Boolean = false,
    val isSaving: Boolean = false,
    /** The stored layout can't be read by this app version; saving replaces it. */
    val replacesUnreadableLayout: Boolean = false,
    val dialog: EditorDialog? = null,
    val message: String? = null,
    /** Saved or discarded: the screen closes. */
    val isClosed: Boolean = false,
) {
    companion object {
        const val DEFAULT_REFERENCE_OPACITY = 0.4f
        const val MIN_REFERENCE_OPACITY = 0.1f
        const val MAX_REFERENCE_OPACITY = 0.9f
    }
}

/**
 * The layout editor (spec §6.5). Holds the [LayoutDraft] (and with it the
 * [io.github.katznmeik.cinexperience.core.model.LayoutEditSession]) for as long as
 * the screen lives, so the edit survives configuration changes; an edit in
 * progress is not restored after process death (the editor then starts from the
 * stored layout again).
 *
 * Undo: every tap, paint stroke, handle drag, stepper tap or row setting (label,
 * numbering, numbering direction, offset, block start) is one step; typing in one
 * text field is one step. Fixed seat labels (`LayoutCell.Seat.fixedLabel`, from
 * the seed) can't be set here; they stay on their seats through every edit, also a
 * change of seat type, and go when the seat is painted as a gap or aisle or its row
 * or column is deleted (undo brings them back).
 *
 * Reference screenshots are copied into app storage when picked; on save the
 * replaced one is deleted, on discard the ones picked in this session are.
 */
class LayoutEditorViewModel(
    private val hallId: Long,
    private val hallRepository: HallRepository,
    private val referenceImages: ReferenceImageStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LayoutEditorUiState())
    val uiState: StateFlow<LayoutEditorUiState> = _uiState.asStateFlow()

    private val history = UndoHistory<LayoutDraft>()

    /** The draft as loaded; "unsaved changes" compares against it. */
    private var baseline: LayoutDraft? = null

    /** The draft before the running gesture (stroke, handle drag), `null` without one. */
    private var gestureBase: LayoutDraft? = null

    /** The running paint stroke, `null` without one. */
    private var stroke: PaintStroke? = null

    /** Screenshots imported in this session; they're deleted unless saved. */
    private val sessionImports = LinkedHashSet<String>()

    /** The hall for a save waiting for the user's confirmation. */
    private var pendingSave: Hall? = null

    private val state: LayoutEditorUiState get() = _uiState.value
    private val draft: LayoutDraft? get() = state.draft

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _uiState.update { it.copy(status = EditorStatus.Loading) }
        val loaded = try {
            hallRepository.observeHallDetail(hallId).first()?.let { detail -> detail to LayoutDraft.start(detail.hall) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Also a hall the model can't start editing, e.g. one whose seat id mark
            // (from a damaged backup) leaves no room for a new grid below SeatLayout.MAX_SEAT_ID.
            _uiState.update { it.copy(status = EditorStatus.LoadFailed) }
            return
        }
        if (loaded == null) {
            _uiState.update { it.copy(status = EditorStatus.NotFound) }
            return
        }
        val (detail, start) = loaded
        baseline = start
        gestureBase = null
        endStroke()
        pendingSave = null
        history.clear()
        _uiState.update {
            LayoutEditorUiState(
                status = EditorStatus.Ready,
                hallName = detail.hall.name,
                tool = it.tool,
                seatKind = it.seatKind,
                referenceOpacity = it.referenceOpacity,
                replacesUnreadableLayout = detail.hall.layoutUnreadable,
            ).withDraft(start, syncInputs = true)
        }
    }

    // ---- Painting and gestures ----

    fun selectTool(tool: CellType) {
        _uiState.update { it.copy(tool = tool) }
    }

    /** Chooses the kind of painted seats (and the seat tool). */
    fun selectSeatKind(kind: SeatKind) {
        _uiState.update { it.copy(tool = CellType.SEAT, seatKind = kind) }
    }

    /** Starts a gesture (paint stroke, handle drag) that becomes one undo step. */
    fun beginGesture() {
        if (gestureBase == null) {
            gestureBase = draft
            endStroke()
        }
        history.breakCoalescing()
    }

    /** Ends the running gesture; records it as one undo step if it changed anything. */
    fun endGesture() {
        val base = gestureBase ?: return
        gestureBase = null
        endStroke()
        val current = draft ?: return
        if (!current.hasSameContentAs(base)) history.record(base)
        publishHistory()
    }

    /**
     * Paints [cells] with the current tool (part of the running gesture, or one step
     * on its own). Painting deselects the screen.
     *
     * Within a gesture, the cells join the running [PaintStroke], whose result
     * doesn't depend on the direction of the stroke (spec §4.3: seats painted in
     * front of a block keep the block's numbers whichever way the finger moves).
     */
    fun paintCells(cells: List<CellPosition>) {
        deselectScreen()
        val tool = state.tool
        val kind = state.seatKind
        if (gestureBase == null) {
            edit { draft -> draft.paintStroke(cells, tool, kind) }
            return
        }
        val running = stroke ?: PaintStroke(draft ?: return, tool, kind).also { stroke = it }
        edit { running.add(cells) }
    }

    private fun endStroke() {
        stroke = null
    }

    /** A tap on a cell: paints it as one undo step (and deselects the screen). */
    fun tapCell(row: Int, column: Int) {
        beginGesture()
        paintCells(listOf(CellPosition(row, column)))
        endGesture()
    }

    // ---- Screen selection ----

    /**
     * Selects the screen (a touch on its bar): it turns green with its handles, and
     * the panel shows its controls. Clears the row selection.
     */
    fun selectScreen() {
        val current = draft ?: return
        if (state.screenSelected || state.referenceMode) return
        history.breakCoalescing()
        _uiState.update { it.copy(screenSelected = true, selectedRow = null).withDraft(current, syncInputs = true) }
    }

    fun deselectScreen() {
        if (state.screenSelected) _uiState.update { it.copy(screenSelected = false) }
    }

    /** A tap on the canvas beside the grid, the row labels and the screen: deselects the screen. */
    fun tapOutside() = deselectScreen()

    /** Screen width from a handle drag (seat widths; snapped and limited). */
    fun dragScreenWidth(width: Float) = edit { it.withScreenWidth(width) }

    /** Screen distance from a handle drag (row depths; snapped and limited). */
    fun dragScreenDistance(distance: Float) = edit { it.withScreenDistance(distance) }

    /** Screen position from a sideways drag of the bar (seat widths; snapped to half seats, within the grid). */
    fun dragScreenOffset(offset: Float) = edit { it.withScreenOffset(offset) }

    // ---- Steppers ----

    fun changeRowCount(delta: Int) = edit { it.withRowCount(it.rowCount + delta) }

    fun changeColumnCount(delta: Int) = edit { it.withColumnCount(it.columnCount + delta) }

    fun changeScreenWidth(delta: Float) = edit { it.withScreenWidth(it.screenWidth + delta) }

    fun changeScreenDistance(delta: Float) = edit { it.withScreenDistance(it.screenDistance + delta) }

    /** "Screen position" stepper: moves the screen by [delta] seat widths (negative = left). */
    fun changeScreenOffset(delta: Float) = edit { it.withScreenOffset(it.screenOffset + delta) }

    // ---- Selected row ----

    /** Selects a row (tap on its label; deselects the screen); selecting it again clears the selection. */
    fun selectRow(index: Int?) {
        val current = draft ?: return
        val target = index?.takeIf { it in 0 until current.rowCount && it != state.selectedRow }
        history.breakCoalescing()
        _uiState.update { it.copy(selectedRow = target, screenSelected = false).withDraft(current, syncInputs = true) }
    }

    /** Adds a row above the selected one, copying its cells, and selects the new row. */
    fun insertRowAbove() {
        val row = state.selectedRow ?: return
        edit(newSelection = row) { it.insertRowCopy(row, template = row) }
    }

    /** Adds a row below the selected one, copying its cells, and selects the new row. */
    fun insertRowBelow() {
        val row = state.selectedRow ?: return
        edit(newSelection = row + 1) { it.insertRowCopy(row + 1, template = row) }
    }

    fun removeSelectedRow() {
        val row = state.selectedRow ?: return
        edit(newSelection = row) { it.removeRow(row) }
    }

    /** Types into the row label field; an empty label means automatic numbering. */
    fun setRowLabel(text: String) {
        val row = state.selectedRow ?: return
        val input = text.take(MAX_LABEL_LENGTH)
        val label = input.trim().ifEmpty { null }
        _uiState.update { it.copy(labelInput = input) }
        edit(coalesceKey = "label:$row", syncInputs = false) { it.updateRow(row) { r -> r.copy(label = label) } }
    }

    fun setNumbering(numbering: SeatNumbering) {
        val row = state.selectedRow ?: return
        edit { it.updateRow(row) { r -> r.copy(numbering = numbering) } }
    }

    /** The selected row's numbering direction (spec §6.5); block starts stay with their blocks. */
    fun setNumberingDirection(direction: NumberingDirection) {
        val row = state.selectedRow ?: return
        edit { it.updateRow(row) { r -> r.copy(numberingDirection = direction) } }
    }

    fun setHalfSeatOffset(enabled: Boolean) {
        val row = state.selectedRow ?: return
        edit { it.updateRow(row) { r -> r.copy(halfSeatOffset = enabled) } }
    }

    /**
     * Types into a block's start number field (digits only, kept as typed); empty =
     * automatic, and so is a value of 0 ("0", "00"): Yorck has no seat 0. Leading
     * zeros are ignored when parsing: "05" starts the block at 5.
     */
    fun setBlockStart(block: Int, text: String) {
        val row = state.selectedRow ?: return
        val input = text.filter { it.isDigit() }.take(MAX_START_DIGITS)
        val start = input.toIntOrNull()?.takeIf { it > 0 }
        _uiState.update { s ->
            val inputs = s.blockStartInputs.toMutableList()
            if (block in inputs.indices) inputs[block] = input
            s.copy(blockStartInputs = inputs)
        }
        edit(coalesceKey = "start:$row:$block", syncInputs = false) {
            it.updateRow(row) { r ->
                val starts = r.blockStartNumbers.toMutableList()
                while (starts.size <= block) starts += null
                starts[block] = start
                r.copy(blockStartNumbers = starts.dropLastWhile { s -> s == null })
            }
        }
    }

    // ---- Undo ----

    fun undo() {
        endGesture()
        val current = draft ?: return
        val previous = history.undo(current) ?: return
        _uiState.update { it.withDraft(previous, syncInputs = true) }
    }

    fun redo() {
        endGesture()
        val current = draft ?: return
        val next = history.redo(current) ?: return
        _uiState.update { it.withDraft(next, syncInputs = true) }
    }

    // ---- Reference screenshot ----

    /** Reference mode aligns the screenshot instead of editing; entering it deselects the screen. */
    fun toggleReferenceMode() {
        _uiState.update { it.copy(referenceMode = !it.referenceMode, screenSelected = false) }
    }

    /** Result of the photo picker; `null` if the user cancelled. */
    fun onReferenceImagePicked(uri: String?) {
        if (uri == null || state.status != EditorStatus.Ready || state.isSaving) return
        _uiState.update { it.copy(isImportingReference = true) }
        viewModelScope.launch {
            try {
                val path = referenceImages.importImage(hallId, uri)
                sessionImports += path
                edit { it.withReferenceImage(path) }
                _uiState.update { it.copy(referenceMode = true, screenSelected = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _uiState.update { it.copy(message = IMPORT_FAILED) }
            } finally {
                _uiState.update { it.copy(isImportingReference = false) }
            }
        }
    }

    /** Removes the screenshot from the draft (its file is deleted once the layout is saved). */
    fun removeReferenceImage() = edit { it.withReferenceImage(null) }

    fun setReferenceOpacity(opacity: Float) {
        _uiState.update {
            it.copy(
                referenceOpacity = opacity.coerceIn(
                    LayoutEditorUiState.MIN_REFERENCE_OPACITY,
                    LayoutEditorUiState.MAX_REFERENCE_OPACITY,
                ),
            )
        }
    }

    /** Placement of the screenshot from the canvas gestures; `null` resets it. */
    fun setReferenceAlignment(alignment: ReferenceAlignment?) {
        _uiState.update { it.copy(referenceAlignment = alignment) }
    }

    // ---- Save / discard ----

    /** Save: warns first if seats with data would disappear or an unreadable layout would be replaced. */
    fun save() {
        endGesture()
        if (state.status != EditorStatus.Ready || state.isSaving || draft == null) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val detail = try {
                hallRepository.observeHallDetail(hallId).first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failSave(SAVE_FAILED, canReload = false)
                return@launch
            }
            val current = draft
            if (detail == null || current == null) {
                failSave(HALL_GONE, canReload = false)
                return@launch
            }
            val withData = seatsWithData(detail.visits, detail.overrides)
            val removed = current.session.removedSeatsWithData(withData)
            val unreadable = detail.hall.layoutUnreadable
            if (removed.isNotEmpty() || unreadable) {
                pendingSave = detail.hall
                // An unreadable layout is replaced as a whole: none of its seats survive
                // (new seats get ids above every id that visits and overrides use).
                val dialog = EditorDialog.ConfirmSave(
                    removedSeats = removed.map { it.label },
                    replacesUnreadableLayout = unreadable,
                    unreadableSeatsWithData = if (unreadable) withData.size else 0,
                )
                _uiState.update { it.copy(isSaving = false, dialog = dialog) }
            } else {
                write(detail.hall)
            }
        }
    }

    fun confirmSave() {
        val hall = pendingSave ?: return
        pendingSave = null
        _uiState.update { it.copy(dialog = null, isSaving = true) }
        viewModelScope.launch { write(hall) }
    }

    private suspend fun write(stored: Hall) {
        val current = draft ?: return
        // The draft's offset is always valid; snapping again guards the repository's check.
        val screenOffset = ScreenOffsetRules.snap(current.screenOffset)
        val unchanged = stored.layout?.rows == current.layout.rows &&
            stored.screenWidth == current.screenWidth &&
            stored.screenDistance == current.screenDistance &&
            stored.screenOffset == screenOffset &&
            stored.referenceImagePath == current.referenceImagePath
        try {
            if (!unchanged) {
                val updated = stored.copy(
                    layout = current.layout,
                    screenWidth = current.screenWidth,
                    screenDistance = current.screenDistance,
                    screenOffset = screenOffset,
                    referenceImagePath = current.referenceImagePath,
                )
                if (!hallRepository.updateHall(updated)) {
                    failSave(HALL_GONE, canReload = false)
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // The stored layout changed since this edit started, or the edit reuses seat ids.
            failSave(STALE_EDIT, canReload = true)
            return
        } catch (e: Exception) {
            failSave(SAVE_FAILED, canReload = false)
            return
        }
        val kept = current.referenceImagePath
        deleteImports(keep = kept)
        stored.referenceImagePath?.takeIf { it != kept }?.let { deleteQuietly(it) }
        _uiState.update { it.copy(isSaving = false, isClosed = true) }
    }

    private fun failSave(message: String, canReload: Boolean) {
        _uiState.update { it.copy(isSaving = false, dialog = EditorDialog.SaveFailed(message, canReload)) }
    }

    /**
     * Back or "Discard": asks first if there are unsaved changes. Ignored while a
     * save is running, so the save is never undercut (it closes the editor itself).
     */
    fun requestDiscard() {
        if (state.isSaving) return
        endGesture()
        if (state.status == EditorStatus.Ready && state.hasUnsavedChanges) {
            _uiState.update { it.copy(dialog = EditorDialog.ConfirmDiscard) }
        } else {
            discard()
        }
    }

    /**
     * Leaves without saving; screenshots picked in this session are deleted.
     * Ignored while a save is running: deleting a just-saved screenshot would leave
     * the hall pointing at a missing file.
     */
    fun discard() {
        if (state.isSaving || state.isClosed) return
        _uiState.update { it.copy(dialog = null) }
        viewModelScope.launch {
            deleteImports(keep = null)
            _uiState.update { it.copy(isClosed = true) }
        }
    }

    /** After a failed save: drop this edit and start again from the stored layout. */
    fun reload() {
        _uiState.update { it.copy(dialog = null) }
        viewModelScope.launch {
            deleteImports(keep = null)
            load()
        }
    }

    fun dismissDialog() {
        pendingSave = null
        _uiState.update { it.copy(dialog = null) }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun deleteImports(keep: String?) {
        val obsolete = sessionImports.filter { it != keep }
        sessionImports.retainAll(setOfNotNull(keep))
        obsolete.forEach { deleteQuietly(it) }
    }

    private suspend fun deleteQuietly(path: String) {
        try {
            referenceImages.delete(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left for the orphan sweep at the next app start.
        }
    }

    // ---- State helpers ----

    /**
     * Applies an edit. Inside a gesture it only updates the draft (the gesture is
     * one step); otherwise it records an undo step (coalesced by [coalesceKey]).
     * An edit that needs a seat id the hall doesn't have left changes nothing and
     * shows [EDIT_FAILED]: only damaged data gets there (a hall whose seat ids are
     * used up, `SeatLayout.MAX_SEAT_ID`), and it must not crash the app. Any other
     * exception is a bug and is not caught. Internal for tests.
     */
    internal fun edit(
        coalesceKey: String? = null,
        syncInputs: Boolean = true,
        newSelection: Int? = state.selectedRow,
        transform: (LayoutDraft) -> LayoutDraft,
    ) {
        val current = draft ?: return
        val edited = try {
            transform(current)
        } catch (e: SeatIdsExhaustedException) {
            _uiState.update { it.copy(message = EDIT_FAILED) }
            return
        }
        if (edited.hasSameContentAs(current)) return
        if (gestureBase == null) history.record(current, coalesceKey)
        _uiState.update { it.copy(selectedRow = newSelection).withDraft(edited, syncInputs) }
    }

    private fun publishHistory() {
        _uiState.update { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
    }

    /** This state showing [newDraft]: labels, row panel, inputs and undo flags derived from it. */
    private fun LayoutEditorUiState.withDraft(newDraft: LayoutDraft, syncInputs: Boolean): LayoutEditorUiState {
        val previous = this.draft
        val labels = this.labels?.takeIf { previous != null && newDraft.layout == previous.layout }
            ?: newDraft.layout.labels()
        val selected = selectedRow?.coerceIn(0, newDraft.rowCount - 1)
        val row = selected?.let { rowSummary(newDraft.layout, labels, it) }
        val referenceChanged = previous != null && newDraft.referenceImagePath != previous.referenceImagePath
        return copy(
            draft = newDraft,
            labels = labels,
            selectedRow = selected,
            row = row,
            labelInput = if (syncInputs) row?.customLabel.orEmpty() else labelInput,
            blockStartInputs = if (syncInputs || row?.blocks?.size != blockStartInputs.size) {
                row?.blocks?.map { it.startNumber?.toString().orEmpty() }.orEmpty()
            } else {
                blockStartInputs
            },
            canUndo = history.canUndo || (gestureBase?.let { !newDraft.hasSameContentAs(it) } ?: false),
            canRedo = history.canRedo,
            hasUnsavedChanges = baseline?.let { !newDraft.hasSameContentAs(it) } ?: false,
            referenceAlignment = if (referenceChanged) null else referenceAlignment,
        )
    }

    companion object {
        const val MAX_LABEL_LENGTH = 6
        const val MAX_START_DIGITS = 4

        const val IMPORT_FAILED = "Couldn't load that image."
        const val EDIT_FAILED = "Couldn't make this change to the layout."
        const val SAVE_FAILED = "Couldn't save the layout. Please try again."
        const val HALL_GONE = "This hall no longer exists."
        const val STALE_EDIT = "The saved layout of this hall changed while you were editing, " +
            "so these changes can't be saved safely. Reload the saved layout and make your changes again."

        fun factory(
            hallId: Long,
            hallRepository: HallRepository,
            referenceImages: ReferenceImageStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { LayoutEditorViewModel(hallId, hallRepository, referenceImages) }
        }
    }
}
