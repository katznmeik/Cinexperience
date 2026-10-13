package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen
import io.github.katznmeik.cinexperience.core.ui.component.NavigationRow
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader

@Composable
internal fun SettingsScreen(
    onOpenAllVisits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CinexScreen(
        title = "Settings",
        subtitle = "Backup export and import, data management and app info will appear here.",
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Data")
            RowGroup {
                NavigationRow(title = "All visits", subtitle = "Placeholder link", onClick = onOpenAllVisits)
            }
        }
    }
}
