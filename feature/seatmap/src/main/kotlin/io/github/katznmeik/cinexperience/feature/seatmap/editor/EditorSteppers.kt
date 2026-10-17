package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Steppers of the screen geometry: under the canvas, or in the [ScreenPanel] while the screen is selected. */
internal fun screenSteppers(draft: LayoutDraft, actions: LayoutEditorActions): List<StepperSpec> = listOf(
    StepperSpec(
        label = "Screen width",
        value = ScreenRules.widthText(draft.screenWidth),
        onMinus = { actions.onScreenWidthStep(-ScreenRules.STEP) },
        onPlus = { actions.onScreenWidthStep(ScreenRules.STEP) },
        canMinus = draft.screenWidth > ScreenRules.MIN_WIDTH,
        canPlus = draft.screenWidth < ScreenRules.maxWidth(draft.columnCount),
    ),
    StepperSpec(
        label = "Screen distance",
        value = ScreenRules.distanceText(draft.screenDistance),
        onMinus = { actions.onScreenDistanceStep(-ScreenRules.STEP) },
        onPlus = { actions.onScreenDistanceStep(ScreenRules.STEP) },
        canMinus = draft.screenDistance > ScreenRules.MIN_DISTANCE,
        canPlus = draft.screenDistance < ScreenRules.MAX_DISTANCE,
    ),
    StepperSpec(
        label = "Screen position",
        value = ScreenRules.offsetText(draft.screenOffset),
        onMinus = { actions.onScreenOffsetStep(-ScreenOffsetRules.STEP) },
        onPlus = { actions.onScreenOffsetStep(ScreenOffsetRules.STEP) },
        canMinus = draft.screenOffset > -ScreenOffsetRules.maxMagnitude(draft.columnCount),
        canPlus = draft.screenOffset < ScreenOffsetRules.maxMagnitude(draft.columnCount),
        minusSymbol = "←",
        plusSymbol = "→",
        minusDescription = "Move the screen left",
        plusDescription = "Move the screen right",
    ),
)

@Composable
internal fun Steppers(draft: LayoutDraft, actions: LayoutEditorActions, includeScreen: Boolean) {
    val steppers = listOf(
        StepperSpec(
            label = "Rows",
            value = draft.rowCount.toString(),
            onMinus = { actions.onRowCount(-1) },
            onPlus = { actions.onRowCount(1) },
            canMinus = draft.rowCount > 1,
            canPlus = draft.rowCount < SeatLayout.MAX_ROWS,
        ),
        StepperSpec(
            label = "Columns",
            value = draft.columnCount.toString(),
            onMinus = { actions.onColumnCount(-1) },
            onPlus = { actions.onColumnCount(1) },
            canMinus = draft.columnCount > 1,
            canPlus = draft.columnCount < SeatLayout.MAX_COLUMNS,
        ),
    ) + if (includeScreen) screenSteppers(draft, actions) else emptyList()
    // Two per row; one per row with large text, so the labels aren't broken mid-word.
    val perRow = if (isLargeText()) 1 else 2
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steppers.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { StepperCard(it, Modifier.weight(1f)) }
            }
        }
    }
}

internal class StepperSpec(
    val label: String,
    val value: String,
    val onMinus: () -> Unit,
    val onPlus: () -> Unit,
    val canMinus: Boolean,
    val canPlus: Boolean,
    val minusSymbol: String = "−",
    val plusSymbol: String = "+",
    val minusDescription: String = "Decrease ${label.lowercase()}",
    val plusDescription: String = "Increase ${label.lowercase()}",
)

@Composable
internal fun StepperCard(spec: StepperSpec, modifier: Modifier = Modifier, container: Color = CinexTheme.colors.surface) {
    val label = spec.label
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            // No end or vertical padding: the buttons' 48 dp touch areas already leave
            // 6 dp around their 36 dp boxes, so the labels keep their width.
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = CinexTheme.colors.textMuted)
            Text(spec.value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = CinexTheme.colors.text)
        }
        StepButton(spec.minusSymbol, spec.minusDescription, spec.canMinus, spec.onMinus)
        StepButton(spec.plusSymbol, spec.plusDescription, spec.canPlus, spec.onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            // Drawn 36 dp, touched 48 dp.
            .minimumInteractiveComponentSize()
            .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CinexTheme.colors.divider)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = CinexTheme.colors.text,
            // The description says it ("Increase rows"); "+" alone would be read as well.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
