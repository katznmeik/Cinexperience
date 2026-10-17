package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.seatmapui.blockName
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.SegmentChipShape
import io.github.katznmeik.cinexperience.core.ui.component.rememberTextWidth
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlin.math.roundToInt

/**
 * The selected screen's controls (spec §6.5), in place of the row panel: how to
 * drag it on the canvas, and steppers for width, distance and position. "Done"
 * deselects it.
 */
@Composable
internal fun ScreenPanel(draft: LayoutDraft, actions: LayoutEditorActions) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Screen", Modifier.weight(1f))
            TextButton(onClick = actions::onDeselectScreen) {
                Text("Done", style = MaterialTheme.typography.labelLarge, color = CinexTheme.colors.text)
            }
        }
        Text(
            "Drag the green handles on the canvas to change the width. Drag the bar up or down " +
                "to change its distance to the first row, or sideways when the screen isn't centred " +
                "over the seats.",
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.textMuted,
        )
        screenSteppers(draft, actions).forEach { spec ->
            StepperCard(spec, Modifier.fillMaxWidth(), container = CinexTheme.colors.surfaceRaised)
        }
    }
}

/** Chip text of a numbering direction in the row panel. */
internal val NumberingDirection.chipText: String
    get() = when (this) {
        NumberingDirection.LEFT_TO_RIGHT -> "Left → right"
        NumberingDirection.RIGHT_TO_LEFT -> "Right → left"
    }

/** What a screen reader announces for the direction chip (the arrow is not read well). */
internal val NumberingDirection.description: String
    get() = when (this) {
        NumberingDirection.LEFT_TO_RIGHT -> "Numbered left to right"
        NumberingDirection.RIGHT_TO_LEFT -> "Numbered right to left"
    }

/**
 * Screen-reader name of a block's "Starts at" field, the block named by its place
 * as drawn ("Right block start number, …"), since the fields follow the numbering
 * direction.
 */
internal fun blockStartDescription(index: Int, blockCount: Int): String {
    val name = blockName(index, blockCount)
    val field = when {
        name == null -> "Start number"
        blockCount <= 3 -> "$name block start number"
        else -> "$name from the left, start number"
    }
    return "$field, empty for automatic"
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
        style = MaterialTheme.typography.titleSmall,
        color = CinexTheme.colors.text,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = CinexTheme.colors.textMuted, modifier = modifier)
}

@Composable
internal fun RowPanel(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    PanelCard {
        val row = state.row
        if (row == null) {
            PanelTitle("Rows")
            Text(
                "Tap a row label on the left of the canvas to edit that row: label, numbering direction, numbering, " +
                    "seat offset and block starts. The label \"-\" means the row has no name: its seats are shown " +
                    "as \"Seat 12\". Tap the screen to change its width, distance and position.",
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
            )
            return@PanelCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle(row.title, Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .toggleable(
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
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Direction", Modifier.padding(end = 4.dp))
            NumberingDirection.entries.forEach { direction ->
                ChoiceChip(
                    text = direction.chipText,
                    selected = row.numberingDirection == direction,
                    onClick = { actions.onNumberingDirection(direction) },
                    shape = SegmentChipShape,
                    modifier = Modifier.semantics { contentDescription = direction.description },
                )
            }
        }
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            FieldLabel("Label", Modifier.widthIn(min = 64.dp))
            SmallTextField(
                value = state.labelInput,
                onValueChange = actions::onRowLabel,
                placeholder = row.autoLabel ?: "–",
                description = "Row label, empty for automatic, a dash for no name",
                width = 72.dp,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            )
            Text(
                row.labelKindText,
                style = MaterialTheme.typography.labelSmall,
                color = CinexTheme.colors.textMuted,
            )
        }
        if (row.blocks.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel("Starts at", Modifier.widthIn(min = 64.dp).padding(top = 15.dp))
                // In numbering order (right block first when the row runs right to left),
                // each with the range its block gets.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.blocksInNumberingOrder.forEach { block ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            SmallTextField(
                                value = state.blockStartInputs.getOrElse(block.index) { "" },
                                onValueChange = { actions.onBlockStart(block.index, it) },
                                placeholder = block.firstNumber?.toString() ?: "–",
                                description = blockStartDescription(block.index, row.blocks.size),
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
            // Drawn 36 dp tall (taller with large text), touched 48 dp.
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .border(1.dp, colors.fieldOutline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            textAlign = TextAlign.Center,
                            // Not read out: the description says what to enter.
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
internal fun ReferencePanel(state: LayoutEditorUiState, hasImage: Boolean, actions: LayoutEditorActions) {
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
            val valueStyle = MaterialTheme.typography.labelMedium
            Text(
                "${(state.referenceOpacity * 100).roundToInt()} %",
                style = valueStyle,
                color = CinexTheme.colors.text,
                // At least as wide as "100 %" at the current text size, so the slider keeps its width while dragging.
                modifier = Modifier.widthIn(min = rememberTextWidth("100 %", valueStyle).coerceAtLeast(40.dp)),
                textAlign = TextAlign.End,
            )
        }
        Text(
            "Drag and pinch on the canvas to line the screenshot up with the grid. Tap a tool above to paint again.",
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.textMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One import at a time, so the screenshot picked last is the one kept.
            OutlinedPillButton(
                text = if (state.isImportingReference) "Loading…" else "Replace",
                onClick = { if (!state.isImportingReference) actions.onPickReference() },
                icon = CinexIcons.Image,
            )
            OutlinedPillButton(text = "Reset position", onClick = actions::onResetReferencePosition, icon = CinexIcons.Fit)
            OutlinedPillButton(text = "Remove", onClick = actions::onRemoveReference, icon = CinexIcons.Delete)
        }
    }
}
