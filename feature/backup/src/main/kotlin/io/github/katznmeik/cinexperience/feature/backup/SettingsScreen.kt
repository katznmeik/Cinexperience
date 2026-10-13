package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

private const val LATER_VERSION = "Coming in a later version"

@Composable
internal fun SettingsEntry(
    viewModel: SettingsViewModel,
    onOpenManage: () -> Unit,
    onOpenAllVisits: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, onOpenManage, onOpenAllVisits)
}

/** Settings (spec §6.7): backup (disabled until M6), data management, about. */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onOpenManage: () -> Unit,
    onOpenAllVisits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CinexScreen(title = "Settings", modifier = modifier) {
        Section("Backup") {
            SettingsRow(CinexIcons.Export, "Export backup", LATER_VERSION, enabled = false)
            RowDivider()
            SettingsRow(CinexIcons.Import, "Import backup", LATER_VERSION, enabled = false)
            RowDivider()
            SettingsRow(CinexIcons.Cloud, "Android auto backup", LATER_VERSION, enabled = false) {
                // The manifest allows backup (spec §8), so it is on; the toggle itself comes in M6.
                Switch(checked = true, onCheckedChange = null, enabled = false)
            }
        }
        Section("Data") {
            val counts = state.counts
            SettingsRow(
                icon = CinexIcons.Cinemas,
                title = "Manage cinemas & halls",
                subtitle = when {
                    counts != null -> "${countText(counts.cinemas, "cinema")} · ${countText(counts.halls, "hall")} · " +
                        "${countText(counts.layoutsDrawn, "layout")} drawn"
                    state.countsError -> "Couldn't load the counts"
                    else -> null
                },
                onClick = onOpenManage,
            )
            RowDivider()
            SettingsRow(
                icon = CinexIcons.List,
                title = "All visits",
                subtitle = counts?.let { if (it.visits == 0) "No visits yet" else countText(it.visits, "visit") },
                onClick = onOpenAllVisits,
            )
        }
        Section("About") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "CINEXPERIENCE",
                    style = CinexTheme.textStyles.cardTitle.copy(fontWeight = FontWeight.ExtraBold),
                    color = CinexTheme.colors.text,
                )
                Text("Version ${state.appVersion}", style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
                Text(
                    "Unofficial app, not affiliated with Yorck Kinos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        RowGroup { content() }
    }
}

/**
 * Icon, title, muted subtitle and a trailing chevron (or [trailing]). Disabled rows
 * are drawn muted and can't be tapped.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor = if (enabled) CinexTheme.colors.text else CinexTheme.colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = titleColor, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
            }
        }
        when {
            trailing != null -> trailing()
            enabled -> Icon(
                CinexIcons.ChevronRight,
                contentDescription = null,
                tint = CinexTheme.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
