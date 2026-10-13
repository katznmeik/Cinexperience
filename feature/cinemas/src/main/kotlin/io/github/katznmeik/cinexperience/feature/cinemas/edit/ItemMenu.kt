package io.github.katznmeik.cinexperience.feature.cinemas.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** One entry of an item's action menu. Disabled entries can explain why. */
internal data class MenuEntry(
    val label: String,
    val explanation: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
)

/** Rename / delete style action menu of a cinema or hall row. */
@Composable
internal fun ItemMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = CinexTheme.colors.surfaceRaised) {
        entries.forEach { entry ->
            DropdownMenuItem(
                enabled = entry.enabled,
                onClick = {
                    onDismiss()
                    entry.onClick()
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.label)
                        entry.explanation?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
                        }
                    }
                },
            )
        }
    }
}
