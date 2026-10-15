package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/**
 * Bottom navigation bar from the mockups: flat, no selection pill; the active tab
 * is drawn in the primary (white) colour, inactive tabs in the muted colour.
 */
@Composable
fun CinexNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CinexTheme.colors.navigationBar)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HorizontalDivider(thickness = 1.dp, color = CinexTheme.colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 76 dp as in the mockups; taller when large text needs it (labels may wrap).
                .heightIn(min = 76.dp)
                .height(IntrinsicSize.Min)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun RowScope.CinexNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    /** False blocks switching tabs (e.g. while a backup import replaces the data). */
    enabled: Boolean = true,
) {
    val color = if (selected) CinexTheme.colors.text else CinexTheme.colors.textMuted
    Column(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .widthIn(min = 64.dp)
            .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        // Four labels side by side: they grow with the font size up to 130 %, beyond
        // that they stay at that size instead of breaking words (spec §7 accessibility).
        val fontScale = LocalDensity.current.fontScale
        val base = CinexTheme.textStyles.navigationLabel
        val style = if (fontScale > MAX_LABEL_SCALE) base.copy(fontSize = base.fontSize * (MAX_LABEL_SCALE / fontScale)) else base
        Text(
            text = label,
            style = style.copy(textAlign = TextAlign.Center),
            color = color,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** The largest font scale the tab labels follow (see [CinexNavigationBarItem]). */
private const val MAX_LABEL_SCALE = 1.3f
