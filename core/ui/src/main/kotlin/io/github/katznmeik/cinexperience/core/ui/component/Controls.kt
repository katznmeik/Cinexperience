package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Top bar of a pushed screen: back arrow on the left, optional icon actions on the right. */
@Composable
fun BackTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarIconButton(icon = CinexIcons.Back, contentDescription = "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** A 48 dp icon button in the text colour, for top bars and list rows. */
@Composable
fun TopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CinexTheme.colors.text,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Small outlined pill button from the mockups, e.g. "+ Add hall": 36 dp tall
 * (taller with large text), 48 dp touch target. Disabled ([enabled] false), its
 * label and icon are muted.
 */
@Composable
fun OutlinedPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = CinexIcons.Add,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) CinexTheme.colors.text else CinexTheme.colors.textMuted
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 36.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CinexTheme.colors.outline),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}

/**
 * Single-choice chip (e.g. sort mode): filled light when selected, outlined otherwise.
 * Drawn 36 dp tall (taller with large text) with a 48 dp touch target. The default is a pill; the layout
 * editor's tool chips pass [SegmentChipShape]. An optional [icon] precedes the
 * text; with an empty [text] the chip shows only the icon (give it a content
 * description through [modifier]). An optional [trailingIcon] follows the text as a
 * mark (e.g. a seat of the booking), sized like the text (16 sp); it is decorative,
 * so the chip's description should say what it marks. Disabled ([enabled] false), it
 * can't be tapped, is announced as disabled and is drawn muted.
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val base = modifier
        .minimumInteractiveComponentSize()
        .heightIn(min = 36.dp)
        .clip(shape)
        .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
    val contentColor = when {
        selected -> CinexTheme.colors.onPrimary
        enabled -> CinexTheme.colors.text
        else -> CinexTheme.colors.textMuted
    }
    Row(
        modifier = if (selected) {
            base.background(if (enabled) CinexTheme.colors.text else CinexTheme.colors.textMuted)
        } else {
            base.border(1.dp, CinexTheme.colors.outline, shape)
        }.padding(horizontal = if (icon == null) 14.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = contentColor,
            )
        }
        if (trailingIcon != null) {
            // A mark beside the text (e.g. a seat of the booking): it grows with the font like the text.
            val markSize = with(LocalDensity.current) { 16.sp.toDp() }
            Icon(trailingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(markSize))
        }
    }
}

/**
 * A pill button like [OutlinedPillButton] whose screen-reader label is
 * [description] instead of its short visible [text], for actions repeated on
 * every row of a list (e.g. "Book on yorck.de: Paper Kites, Sat 11:00").
 */
@Composable
fun ActionPillButton(
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 36.dp)
            .clip(shape)
            .border(1.dp, CinexTheme.colors.fieldOutline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = CinexTheme.colors.text, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.text)
    }
}

/**
 * Multi-select chip (e.g. the Program's cinema and version filters): looks like
 * [ChoiceChip], but is a checkbox for screen readers ("checked / not checked").
 */
@Composable
fun ToggleChip(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val base = modifier
        .minimumInteractiveComponentSize()
        .heightIn(min = 36.dp)
        .clip(shape)
        .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox)
    val contentColor = if (checked) CinexTheme.colors.onPrimary else CinexTheme.colors.text
    Row(
        modifier = if (checked) {
            base.background(CinexTheme.colors.text)
        } else {
            base.border(1.dp, CinexTheme.colors.outline, shape)
        }.padding(horizontal = if (icon == null) 14.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium),
            color = contentColor,
        )
    }
}

/**
 * A small monochrome label, e.g. a showing's version ("OmU") or event ("Sneak"):
 * text in a thin outline, not interactive. [description] replaces the text for
 * screen readers (e.g. "OmU, German subtitles").
 */
@Composable
fun LabelChip(text: String, modifier: Modifier = Modifier, description: String? = null) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = CinexTheme.colors.text,
        modifier = modifier
            .border(1.dp, CinexTheme.colors.fieldOutline, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier),
    )
}

/** Shape of segmented tool chips (layout editor mockup): rounded rectangle, not a pill. */
val SegmentChipShape: Shape = RoundedCornerShape(10.dp)

/**
 * Row of [ChoiceChip]s with the mockup spacing. Wraps onto further lines when the
 * chips don't fit (large text); inside a horizontal scroll it stays one line.
 */
@Composable
fun ChoiceChipRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
