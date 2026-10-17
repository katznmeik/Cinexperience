package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapViewState
import io.github.katznmeik.cinexperience.core.seatmapui.icon
import io.github.katznmeik.cinexperience.core.seatmapui.rememberSeatMapViewState
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.FormMessage
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.SegmentChipShape
import io.github.katznmeik.cinexperience.core.ui.component.rememberLoadedImage
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.navigation.CloseOnce
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Everything the editor screen can ask for; no-op defaults make previews and screenshots easy. */
internal interface LayoutEditorActions : EditorCanvasActions {
    fun onDiscard() {}
    fun onSave() {}
    fun onSelectTool(tool: CellType) {}
    fun onSelectSeatKind(kind: SeatKind) {}
    fun onUndo() {}
    fun onRedo() {}
    fun onToggleReferenceMode() {}
    fun onPickReference() {}
    fun onRemoveReference() {}
    fun onReferenceOpacity(opacity: Float) {}
    fun onResetReferencePosition() {}
    fun onRowLabel(text: String) {}
    fun onNumbering(numbering: SeatNumbering) {}
    fun onNumberingDirection(direction: NumberingDirection) {}
    fun onHalfSeatOffset(enabled: Boolean) {}
    fun onBlockStart(block: Int, text: String) {}
    fun onInsertRowAbove() {}
    fun onInsertRowBelow() {}
    fun onRemoveRow() {}
    fun onRowCount(delta: Int) {}
    fun onColumnCount(delta: Int) {}
    fun onScreenWidthStep(delta: Float) {}
    fun onScreenDistanceStep(delta: Float) {}
    fun onScreenOffsetStep(delta: Float) {}

    /** "Done" in the screen panel. */
    fun onDeselectScreen() {}
    fun onConfirmDiscard() {}
    fun onConfirmSave() {}
    fun onDismissDialog() {}
    fun onReload() {}
    fun onMessageShown() {}

    override fun onBeginGesture() {}
    override fun onEndGesture() {}
    override fun onTapCell(row: Int, column: Int) {}
    override fun onPaintCells(cells: List<CellPosition>) {}
    override fun onSelectRow(row: Int) {}
    override fun onSelectScreen() {}
    override fun onTapOutside() {}
    override fun onScreenWidth(width: Float) {}
    override fun onScreenDistance(distance: Float) {}
    override fun onScreenOffset(offset: Float) {}
    override fun onReferenceAlignment(alignment: ReferenceAlignment) {}
}

private fun LayoutEditorViewModel.actions(pickReference: () -> Unit) = object : LayoutEditorActions {
    override fun onDiscard() = requestDiscard()
    override fun onSave() = save()
    override fun onSelectTool(tool: CellType) = selectTool(tool)
    override fun onSelectSeatKind(kind: SeatKind) = selectSeatKind(kind)
    override fun onUndo() = undo()
    override fun onRedo() = redo()
    override fun onToggleReferenceMode() = toggleReferenceMode()
    override fun onPickReference() = pickReference()
    override fun onRemoveReference() = removeReferenceImage()
    override fun onReferenceOpacity(opacity: Float) = setReferenceOpacity(opacity)
    override fun onResetReferencePosition() = setReferenceAlignment(null)
    override fun onRowLabel(text: String) = setRowLabel(text)
    override fun onNumbering(numbering: SeatNumbering) = setNumbering(numbering)
    override fun onNumberingDirection(direction: NumberingDirection) = setNumberingDirection(direction)
    override fun onHalfSeatOffset(enabled: Boolean) = setHalfSeatOffset(enabled)
    override fun onBlockStart(block: Int, text: String) = setBlockStart(block, text)
    override fun onInsertRowAbove() = insertRowAbove()
    override fun onInsertRowBelow() = insertRowBelow()
    override fun onRemoveRow() = removeSelectedRow()
    override fun onRowCount(delta: Int) = changeRowCount(delta)
    override fun onColumnCount(delta: Int) = changeColumnCount(delta)
    override fun onScreenWidthStep(delta: Float) = changeScreenWidth(delta)
    override fun onScreenDistanceStep(delta: Float) = changeScreenDistance(delta)
    override fun onScreenOffsetStep(delta: Float) = changeScreenOffset(delta)
    override fun onDeselectScreen() = deselectScreen()
    override fun onConfirmDiscard() = discard()
    override fun onConfirmSave() = confirmSave()
    override fun onDismissDialog() = dismissDialog()
    override fun onReload() = reload()
    override fun onMessageShown() = messageShown()
    override fun onBeginGesture() = beginGesture()
    override fun onEndGesture() = endGesture()
    override fun onTapCell(row: Int, column: Int) = tapCell(row, column)
    override fun onPaintCells(cells: List<CellPosition>) = paintCells(cells)
    override fun onSelectRow(row: Int) = selectRow(row)
    override fun onSelectScreen() = selectScreen()
    override fun onTapOutside() = tapOutside()
    override fun onScreenWidth(width: Float) = dragScreenWidth(width)
    override fun onScreenDistance(distance: Float) = dragScreenDistance(distance)
    override fun onScreenOffset(offset: Float) = dragScreenOffset(offset)
    override fun onReferenceAlignment(alignment: ReferenceAlignment) = setReferenceAlignment(alignment)
}

@Composable
internal fun LayoutEditorEntry(
    viewModel: LayoutEditorViewModel,
    referenceImages: ReferenceImageStore,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onReferenceImagePicked(uri?.toString())
    }
    val actions = remember(viewModel, picker) {
        viewModel.actions(pickReference = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
    }
    val path = state.draft?.referenceImagePath
    val referenceBitmap by rememberLoadedImage(path) { referenceImages.loadImage(it) }
    CloseOnce(state.isClosed, onClose)
    // Stays enabled while saving: Back is then swallowed (the ViewModel ignores it)
    // instead of leaving mid-save.
    BackHandler(enabled = !state.isClosed) { actions.onDiscard() }
    LayoutEditorScreen(state, referenceBitmap, actions)
}

/** The layout editor (spec §6.5), stateless. */
@Composable
internal fun LayoutEditorScreen(
    state: LayoutEditorUiState,
    referenceImage: ImageBitmap?,
    actions: LayoutEditorActions,
    modifier: Modifier = Modifier,
    viewState: SeatMapViewState = rememberSeatMapViewState(),
) {
    val snackbarHostState = rememberMessageSnackbarState(state.message, actions::onMessageShown)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                hallName = state.hallName,
                // Not while a screenshot is still being copied (the panel shows "Loading…").
                canSave = state.status == EditorStatus.Ready && !state.isSaving && !state.isImportingReference,
                canDiscard = !state.isSaving,
                actions = actions,
            )
            when (state.status) {
                EditorStatus.Loading -> Unit
                EditorStatus.NotFound -> FormMessage(LayoutEditorViewModel.HALL_GONE)
                EditorStatus.LoadFailed -> {
                    FormMessage("Couldn't load this hall.")
                    TextButton(onClick = actions::onReload, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Try again") }
                }
                EditorStatus.Ready -> ReadyContent(state, referenceImage, actions, viewState)
            }
        }
        MessageSnackbarHost(snackbarHostState)
    }
    EditorDialogHost(state.dialog, actions)
}

@Composable
private fun EditorTopBar(hallName: String, canSave: Boolean, canDiscard: Boolean, actions: LayoutEditorActions) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Disabled while saving: the save closes the editor itself.
            TextButton(onClick = actions::onDiscard, enabled = canDiscard) {
                Text(
                    "Discard",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.text.copy(alpha = if (canDiscard) 0.8f else 0.35f),
                )
            }
            Text(
                text = "${hallName.uppercase()} · LAYOUT",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = CinexTheme.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
            )
            Button(
                onClick = actions::onSave,
                enabled = canSave,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 40.dp),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text("Save", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = CinexTheme.colors.divider)
    }
}

@Composable
private fun ReadyContent(
    state: LayoutEditorUiState,
    referenceImage: ImageBitmap?,
    actions: LayoutEditorActions,
    viewState: SeatMapViewState,
) {
    val draft = state.draft ?: return
    val labels = state.labels ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ToolRow(state, actions)
        if (state.tool == CellType.SEAT && !state.referenceMode) SeatKindRow(state.seatKind, actions)
        if (state.replacesUnreadableLayout) {
            Notice("The saved layout can't be shown by this version of the app. Saving replaces it with this one.")
        }
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinexTheme.colors.canvas),
        ) {
            EditorCanvas(
                draft = draft,
                labels = labels,
                selectedRow = state.selectedRow,
                referenceMode = state.referenceMode,
                referenceImage = referenceImage,
                referenceOpacity = state.referenceOpacity,
                referenceAlignment = state.referenceAlignment,
                actions = actions,
                viewState = viewState,
                screenSelected = state.screenSelected,
                maxHeight = canvasMaxHeight(),
                modifier = Modifier.fillMaxWidth(),
            )
            // Derived, so zooming and panning don't recompose the whole editor every frame.
            val adjusted by remember(viewState) { derivedStateOf { viewState.isAdjusted } }
            CanvasFooter(
                hint = canvasHint(state.referenceMode, referenceImage != null, state.referenceOpacity, state.screenSelected),
                showFit = adjusted,
                onFit = viewState::reset,
            )
        }
        when {
            state.referenceMode -> ReferencePanel(state, hasImage = draft.referenceImagePath != null, actions)
            state.screenSelected -> ScreenPanel(draft, actions)
            else -> RowPanel(state, actions)
        }
        // The screen's steppers move into the screen panel while the screen is selected.
        Steppers(draft, actions, includeScreen = !state.screenSelected)
    }
}

/**
 * The line under the drawing area: the canvas hint and, while the view is zoomed
 * or panned, the "Fit" control. Outside the drawing, so neither ever covers the
 * grid, the screen bar or its handles. Always as tall as the "Fit" control's
 * touch area, so nothing below jumps when it appears.
 */
@Composable
private fun CanvasFooter(hint: String, showFit: Boolean, onFit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = CinexTheme.colors.text.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
        if (showFit) FitButton(onFit)
    }
}

/** Small labelled pill "Fit" (screen readers: "Fit to view"), drawn 32 dp tall, touched 48 dp. */
@Composable
private fun FitButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .clip(shape)
            .border(1.dp, CinexTheme.colors.outline, shape)
            .semantics { contentDescription = "Fit to view" }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(CinexIcons.Fit, contentDescription = null, tint = CinexTheme.colors.text, modifier = Modifier.size(16.dp))
        Text(
            "Fit",
            style = MaterialTheme.typography.labelMedium,
            color = CinexTheme.colors.text,
            // The description says it ("Fit to view").
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** The canvas grows with the fitted grid up to about half the screen height. */
@Composable
private fun canvasMaxHeight(): Dp {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return (windowHeight * 0.5f).coerceIn(300.dp, 560.dp)
}

/** Paint tools on the left, undo / redo / reference on the right; wraps with large text. */
@Composable
private fun ToolRow(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val painting = !state.referenceMode
        listOf(CellType.SEAT to "Seat", CellType.GAP to "Gap", CellType.AISLE to "Aisle").forEach { (tool, label) ->
            ChoiceChip(
                text = label,
                selected = painting && state.tool == tool,
                onClick = {
                    if (state.referenceMode) actions.onToggleReferenceMode()
                    actions.onSelectTool(tool)
                },
                shape = SegmentChipShape,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::onUndo, enabled = state.canUndo) {
                Icon(
                    CinexIcons.Undo,
                    contentDescription = "Undo",
                    tint = CinexTheme.colors.text,
                    modifier = Modifier
                        .size(20.dp)
                        .alpha(if (state.canUndo) 1f else 0.35f),
                )
            }
            IconButton(onClick = actions::onRedo, enabled = state.canRedo) {
                Icon(
                    CinexIcons.Redo,
                    contentDescription = "Redo",
                    tint = CinexTheme.colors.text,
                    modifier = Modifier
                        .size(20.dp)
                        .alpha(if (state.canRedo) 1f else 0.35f),
                )
            }
            ReferenceToggle(
                selected = state.referenceMode,
                hasImage = state.draft?.referenceImagePath != null,
                onClick = actions::onToggleReferenceMode,
            )
        }
    }
}

/** The outlined "Ref" toggle: filled while the canvas aligns the screenshot. */
@Composable
private fun ReferenceToggle(selected: Boolean, hasImage: Boolean, onClick: () -> Unit) {
    val colors = CinexTheme.colors
    val content = if (selected) colors.onPrimary else colors.text
    val base = Modifier
        .minimumInteractiveComponentSize()
        .heightIn(min = 36.dp)
        .clip(SegmentChipShape)
        .toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
        .semantics { contentDescription = "Reference screenshot" }
    Row(
        modifier = if (selected) {
            base.background(colors.text)
        } else {
            base.border(1.dp, if (hasImage) colors.text else colors.outline, SegmentChipShape)
        }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CinexIcons.Image, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text("Ref", style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun SeatKindRow(selected: SeatKind, actions: LayoutEditorActions) {
    // Wraps onto a second line when the chips don't fit (large text), with the
    // seat picker's gap between the lines.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Seat type", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
        SeatKind.entries.forEach { kind ->
            // Special kinds show their name only while selected, so the row fits a phone.
            val showName = kind == SeatKind.STANDARD || kind == selected
            ChoiceChip(
                text = if (showName) kind.editorName else "",
                selected = kind == selected,
                onClick = { actions.onSelectSeatKind(kind) },
                shape = SegmentChipShape,
                icon = kind.icon,
                modifier = Modifier.semantics { contentDescription = "${kind.editorName} seats" },
            )
        }
    }
}

private val SeatKind.editorName: String
    get() = when (this) {
        SeatKind.STANDARD -> "Standard"
        SeatKind.WHEELCHAIR -> "Wheelchair"
        SeatKind.COMPANION -> "Companion"
        SeatKind.SOFA -> "Sofa"
    }

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = CinexTheme.colors.text,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, CinexTheme.colors.outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}
