package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.StarRatingInput
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/**
 * Two fields side by side; with large text one below the other, so their
 * texts ("Choose cinema first", a film title) aren't cut off.
 */
@Composable
internal fun FieldPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (isLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun LabelledField(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
        content()
    }
}

/** The 48 dp surface box of the form's fields. */
@Composable
internal fun FieldBox(
    onClick: (() -> Unit)?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(FieldShape)
            .background(CinexTheme.colors.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(
                // The description replaces the visible texts (e.g. "Hall, not chosen" instead of "Choose").
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics {
                        this.contentDescription = contentDescription
                        if (onClick != null) {
                            role = Role.Button
                            onClick {
                                onClick()
                                true
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val FieldShape = RoundedCornerShape(12.dp)

@Composable
internal fun FieldText(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium),
        color = if (muted) CinexTheme.colors.textMuted else CinexTheme.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A dropdown-like field ("delphi LUX ▾") opening a menu of [options] (id to name). */
@Composable
internal fun DropdownField(
    text: String?,
    placeholder: String,
    contentDescription: String,
    options: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldBox(
            onClick = if (options.isEmpty()) null else ({ open = true }),
            contentDescription = when {
                text != null -> "$contentDescription, $text"
                options.isEmpty() -> "$contentDescription, not chosen, ${placeholder.lowercase()}"
                else -> "$contentDescription, not chosen"
            },
        ) {
            FieldText(text ?: placeholder, Modifier.weight(1f), muted = text == null)
            Icon(
                CinexIcons.ChevronDown,
                contentDescription = null,
                tint = if (options.isEmpty()) CinexTheme.colors.outline else CinexTheme.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = CinexTheme.colors.surfaceRaised,
        ) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

/** A plain text field in the form's field style, with a muted [placeholder]. */
@Composable
internal fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    contentDescription: String,
    singleLine: Boolean,
    minHeight: Dp = 48.dp,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(color = CinexTheme.colors.text, fontWeight = FontWeight.Medium)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(CinexTheme.colors.text),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clip(FieldShape)
                    .background(CinexTheme.colors.surface)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    // Not read out: the field's description already names it.
                    Text(
                        placeholder,
                        style = style.copy(fontWeight = FontWeight.Normal),
                        color = CinexTheme.colors.textMuted,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                inner()
            }
        },
    )
}

/** One rating row: label on the left, five stars on the right, at least 52 dp tall. */
@Composable
internal fun StarRow(label: String, rating: Int?, onRate: (Int) -> Unit) {
    // With large text the label goes above the stars, so it isn't broken mid-word.
    if (isLargeText()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
            StarRatingInput(rating = rating, onRate = onRate, label = label, modifier = Modifier.align(Alignment.End))
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text, modifier = Modifier.weight(1f))
        StarRatingInput(rating = rating, onRate = onRate, label = label)
    }
}

@Composable
internal fun CollapsibleHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(text, Modifier.weight(1f))
        Icon(
            if (expanded) CinexIcons.ChevronDown else CinexIcons.ChevronRight,
            contentDescription = null,
            tint = CinexTheme.colors.textMuted,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(18.dp),
        )
    }
}
