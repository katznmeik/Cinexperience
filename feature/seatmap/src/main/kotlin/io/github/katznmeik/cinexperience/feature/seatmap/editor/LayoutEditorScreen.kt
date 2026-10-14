package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapViewState
import io.github.katznmeik.cinexperience.core.seatmapui.icon
import io.github.katznmeik.cinexperience.core.seatmapui.rememberSeatMapViewState
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.SegmentChipShape
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

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
    fun onHalfSeatOffset(enabled: Boolean) {}
    fun onBlockStart(block: Int, text: String) {}
    fun onInsertRowAbove() {}
    fun onInsertRowBelow() {}
    fun onRemoveRow() {}
    fun onRowCount(delta: Int) {}
    fun onColumnCount(delta: Int) {}
    fun onScreenWidthStep(delta: Float) {}
    fun onScreenDistanceStep(delta: Float) {}
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
    override fun onScreenWidth(width: Float) {}
    override fun onScreenDistance(distance: Float) {}
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
    override fun onHalfSeatOffset(enabled: Boolean) = setHalfSeatOffset(enabled)
    override fun onBlockStart(block: Int, text: String) = setBlockStart(block, text)
    override fun onInsertRowAbove() = insertRowAbove()
    override fun onInsertRowBelow() = insertRowBelow()
    override fun onRemoveRow() = removeSelectedRow()
    override fun onRowCount(delta: Int) = changeRowCount(delta)
    override fun onColumnCount(delta: Int) = changeColumnCount(delta)
    override fun onScreenWidthStep(delta: Float) = changeScreenWidth(delta)
    override fun onScreenDistanceStep(delta: Float) = changeScreenDistance(delta)
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
    override fun onScreenWidth(width: Float) = dragScreenWidth(width)
    override fun onScreenDistance(distance: Float) = dragScreenDistance(distance)
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
    val referenceBitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            try {
                referenceImages.loadImage(it)?.asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    // Close exactly once, also if the screen is recreated while it leaves.
    var closeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isClosed) {
        if (state.isClosed && !closeHandled) {
            closeHandled = true
            onClose()
        }
    }
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
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.onMessageShown()
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                hallName = state.hallName,
                canSave = state.status == EditorStatus.Ready && !state.isSaving,
                canDiscard = !state.isSaving,
                actions = actions,
            )
            when (state.status) {
                EditorStatus.Loading -> Unit
                EditorStatus.NotFound -> Message("This hall no longer exists.")
                EditorStatus.LoadFailed -> {
                    Message("Couldn't load this hall.")
                    TextButton(onClick = actions::onReload, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Try again") }
                }
                EditorStatus.Ready -> ReadyContent(state, referenceImage, actions, viewState)
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
                color = CinexTheme.colors.text,
                maxLines = 1,
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
                modifier = Modifier.height(40.dp),
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
        Box(
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
                maxHeight = canvasMaxHeight(),
                modifier = Modifier.fillMaxWidth(),
            )
            // Derived, so zooming and panning don't recompose the whole editor every frame.
            val adjusted by remember(viewState) { derivedStateOf { viewState.isAdjusted } }
            if (adjusted) {
                IconButton(onClick = viewState::reset, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(CinexIcons.Fit, contentDescription = "Fit to view", tint = CinexTheme.colors.text, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (state.referenceMode) {
            ReferencePanel(state, hasImage = draft.referenceImagePath != null, actions)
        } else {
            RowPanel(state, actions)
        }
        Steppers(draft, actions)
    }
}

/** The canvas grows with the fitted grid up to about half the screen height. */
@Composable
private fun canvasMaxHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceIn(300.dp, 560.dp)

@Composable
private fun ToolRow(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
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
        IconButton(onClick = actions::onUndo, enabled = state.canUndo, modifier = Modifier.size(40.dp)) {
            Icon(CinexIcons.Undo, contentDescription = "Undo", tint = CinexTheme.colors.text, modifier = Modifier
                .size(20.dp)
                .alpha(if (state.canUndo) 1f else 0.35f))
        }
        IconButton(onClick = actions::onRedo, enabled = state.canRedo, modifier = Modifier.size(40.dp)) {
            Icon(CinexIcons.Redo, contentDescription = "Redo", tint = CinexTheme.colors.text, modifier = Modifier
                .size(20.dp)
                .alpha(if (state.canRedo) 1f else 0.35f))
        }
        ReferenceToggle(selected = state.referenceMode, hasImage = state.draft?.referenceImagePath != null, onClick = actions::onToggleReferenceMode)
    }
}

/** The outlined "Ref" toggle: filled while the canvas aligns the screenshot. */
@Composable
private fun ReferenceToggle(selected: Boolean, hasImage: Boolean, onClick: () -> Unit) {
    val colors = CinexTheme.colors
    val content = if (selected) colors.onPrimary else colors.text
    val base = Modifier
        .minimumInteractiveComponentSize()
        .height(36.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CinexTheme.colors.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
        color = CinexTheme.colors.text,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = CinexTheme.colors.textMuted, modifier = modifier)
}

@Composable
private fun RowPanel(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    PanelCard {
        val row = state.row
        if (row == null) {
            PanelTitle("Rows")
            Text(
                "Tap a row label on the left of the canvas to edit that row: label, numbering, seat offset and block starts.",
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
            )
            return@PanelCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle(row.title, Modifier.weight(1f))
            Row(
                modifier = Modifier.toggleable(
                    value = row.halfSeatOffset,
                    role = Role.Checkbox,
                    onValueChange = actions::onHalfSeatOffset,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("½ seat offset", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.text)
                Checkbox(checked = row.halfSeatOffset, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Numbering", Modifier.padding(end = 4.dp))
            ChoiceChip(
                text = "Continuous",
                selected = row.numbering == SeatNumbering.CONTINUOUS,
                onClick = { actions.onNumbering(SeatNumbering.CONTINUOUS) },
                shape = SegmentChipShape,
            )
            ChoiceChip(
                text = "Restart per block",
                selected = row.numbering == SeatNumbering.RESTART_PER_BLOCK,
                onClick = { actions.onNumbering(SeatNumbering.RESTART_PER_BLOCK) },
                shape = SegmentChipShape,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Label", Modifier.width(64.dp))
            SmallTextField(
                value = state.labelInput,
                onValueChange = actions::onRowLabel,
                placeholder = row.autoLabel ?: "–",
                description = "Row label, empty for automatic",
                width = 72.dp,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            )
            Text(
                if (row.customLabel == null) "automatic" else "custom",
                style = MaterialTheme.typography.labelSmall,
                color = CinexTheme.colors.textMuted,
            )
        }
        if (row.blocks.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel("Starts at", Modifier.width(64.dp).padding(top = 9.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.blocks.forEach { block ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            SmallTextField(
                                value = state.blockStartInputs.getOrElse(block.index) { "" },
                                onValueChange = { actions.onBlockStart(block.index, it) },
                                placeholder = block.firstNumber?.toString() ?: "–",
                                description = "Block ${block.index + 1} start number, empty for automatic",
                                width = 56.dp,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            )
                            Text(block.rangeText, style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.textMuted)
                        }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedPillButton(text = "Row above", onClick = actions::onInsertRowAbove)
            OutlinedPillButton(text = "Row below", onClick = actions::onInsertRowBelow)
            if ((state.draft?.rowCount ?: 0) > 1) {
                OutlinedPillButton(text = "Delete row", onClick = actions::onRemoveRow, icon = CinexIcons.Delete)
            }
        }
    }
}

@Composable
private fun SmallTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String,
    width: Dp,
    keyboardOptions: KeyboardOptions,
) {
    val colors = CinexTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.text),
        keyboardOptions = keyboardOptions,
        modifier = Modifier
            .width(width)
            .height(36.dp)
            .semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, textAlign = TextAlign.Center)
                }
                inner()
            }
        },
    )
}

@Composable
private fun ReferencePanel(state: LayoutEditorUiState, hasImage: Boolean, actions: LayoutEditorActions) {
    PanelCard {
        PanelTitle("Reference screenshot")
        if (!hasImage) {
            Text(
                "Add a screenshot of this hall's seat plan from the Yorck app. It is shown behind the grid, " +
                    "so you can trace the rows, aisles and staggered seats.",
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
            )
            Button(onClick = actions::onPickReference, enabled = !state.isImportingReference, shape = RoundedCornerShape(12.dp)) {
                Text(if (state.isImportingReference) "Loading…" else "Choose screenshot", style = MaterialTheme.typography.labelLarge)
            }
            return@PanelCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Opacity")
            Slider(
                value = state.referenceOpacity,
                onValueChange = actions::onReferenceOpacity,
                valueRange = LayoutEditorUiState.MIN_REFERENCE_OPACITY..LayoutEditorUiState.MAX_REFERENCE_OPACITY,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Screenshot opacity" },
            )
            Text(
                "${(state.referenceOpacity * 100).roundToInt()} %",
                style = MaterialTheme.typography.labelMedium,
                color = CinexTheme.colors.text,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.End,
            )
        }
        Text(
            "Drag and pinch on the canvas to line the screenshot up with the grid. Tap a tool above to paint again.",
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.textMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedPillButton(text = if (state.isImportingReference) "Loading…" else "Replace", onClick = actions::onPickReference, icon = CinexIcons.Image)
            OutlinedPillButton(text = "Reset position", onClick = actions::onResetReferencePosition, icon = CinexIcons.Fit)
            OutlinedPillButton(text = "Remove", onClick = actions::onRemoveReference, icon = CinexIcons.Delete)
        }
    }
}

@Composable
private fun Steppers(draft: LayoutDraft, actions: LayoutEditorActions) {
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepperCard(
                label = "Rows",
                value = draft.rowCount.toString(),
                onMinus = { actions.onRowCount(-1) },
                onPlus = { actions.onRowCount(1) },
                canMinus = draft.rowCount > 1,
                canPlus = draft.rowCount < LayoutDraft.MAX_ROWS,
                modifier = Modifier.weight(1f),
            )
            StepperCard(
                label = "Columns",
                value = draft.columnCount.toString(),
                onMinus = { actions.onColumnCount(-1) },
                onPlus = { actions.onColumnCount(1) },
                canMinus = draft.columnCount > 1,
                canPlus = draft.columnCount < LayoutDraft.MAX_COLUMNS,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepperCard(
                label = "Screen width",
                value = ScreenRules.widthText(draft.screenWidth),
                onMinus = { actions.onScreenWidthStep(-ScreenRules.STEP) },
                onPlus = { actions.onScreenWidthStep(ScreenRules.STEP) },
                canMinus = draft.screenWidth > ScreenRules.MIN_WIDTH,
                canPlus = draft.screenWidth < ScreenRules.maxWidth(draft.columnCount),
                modifier = Modifier.weight(1f),
            )
            StepperCard(
                label = "Screen distance",
                value = ScreenRules.distanceText(draft.screenDistance),
                onMinus = { actions.onScreenDistanceStep(-ScreenRules.STEP) },
                onPlus = { actions.onScreenDistanceStep(ScreenRules.STEP) },
                canMinus = draft.screenDistance > ScreenRules.MIN_DISTANCE,
                canPlus = draft.screenDistance < ScreenRules.MAX_DISTANCE,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StepperCard(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CinexTheme.colors.surface)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = CinexTheme.colors.textMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = CinexTheme.colors.text)
        }
        StepButton("−", "Decrease ${label.lowercase()}", canMinus, onMinus)
        Spacer(Modifier.width(2.dp))
        StepButton("+", "Increase ${label.lowercase()}", canPlus, onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CinexTheme.colors.divider)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = CinexTheme.colors.text)
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** Confirmation and error dialogs (no text fields, so they also work in UI tests). */
@Composable
private fun EditorDialogHost(dialog: EditorDialog?, actions: LayoutEditorActions) {
    when (dialog) {
        null -> Unit
        EditorDialog.ConfirmDiscard -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Discard changes?") },
            text = { DialogText("Your changes to this layout will be lost.") },
            confirmButton = { TextButton(onClick = actions::onConfirmDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } },
        )
        is EditorDialog.ConfirmSave -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text(if (dialog.removedSeats.isEmpty()) "Replace the saved layout?" else "Remove seats with visits?") },
            text = { DialogText(confirmSaveText(dialog)) },
            confirmButton = { TextButton(onClick = actions::onConfirmSave) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } },
        )
        is EditorDialog.SaveFailed -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Couldn't save") },
            text = { DialogText(dialog.message) },
            confirmButton = {
                if (dialog.canReload) {
                    TextButton(onClick = actions::onReload) { Text("Reload") }
                } else {
                    TextButton(onClick = actions::onDismissDialog) { Text("OK") }
                }
            },
            dismissButton = if (dialog.canReload) {
                { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } }
            } else {
                null
            },
        )
    }
}

@Composable
private fun DialogText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted)
}

/** Body of the §6.5 warning: which seats with data would disappear. */
internal fun confirmSaveText(dialog: EditorDialog.ConfirmSave): String = buildString {
    val seats = dialog.removedSeats
    if (seats.isNotEmpty()) {
        val shown = seats.take(MAX_LISTED_SEATS).joinToString("\n") { "• ${it.description}" }
        val more = seats.size - MAX_LISTED_SEATS
        append(if (seats.size == 1) "This seat has visits or seat settings and will be removed:\n" else "These ${seats.size} seats have visits or seat settings and will be removed:\n")
        append(shown)
        if (more > 0) append("\n… and $more more")
        append("\n\nTheir visits are kept, without a seat. Colours and favourites of these seats are deleted.")
    }
    if (dialog.replacesUnreadableLayout) {
        if (isNotEmpty()) append("\n\n")
        append("The saved layout can't be read by this version of the app and will be replaced.")
        when (val count = dialog.unreadableSeatsWithData) {
            0 -> Unit
            1 -> append(" 1 of its seats has visits or seat settings: its visits will keep no seat, and its colour and favourite are deleted.")
            else -> append(
                " $count of its seats have visits or seat settings: their visits will keep no seat, " +
                    "and their colours and favourites are deleted.",
            )
        }
    }
}

private const val MAX_LISTED_SEATS = 12
