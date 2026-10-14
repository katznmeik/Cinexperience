package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.Role
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
                .height(76.dp)
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
        Text(text = label, style = CinexTheme.textStyles.navigationLabel, color = color)
    }
}
