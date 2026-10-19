package io.github.katznmeik.cinexperience.feature.backup

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChipRow
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Reminder callbacks of the Settings screen (spec §6.7), implemented by [ReminderSettingsViewModel] and the permission request. */
internal interface ReminderSettingsActions {
    fun onSwitchChanged(kind: ReminderKind, on: Boolean) {}
    fun onLeadTimeChanged(leadTime: ReminderLeadTime) {}
    fun onOpenNotificationSettings() {}

    companion object {
        val None = object : ReminderSettingsActions {}
    }
}

/** The texts of Settings → Reminders. */
internal object ReminderTexts {
    const val BEFORE_SHOWING = "Before the showing"
    const val BEFORE_SHOWING_SUBTITLE = "For visits with a start time"
    const val LEAD_TIME_LABEL = "How long before the start"
    const val RATE_VISIT = "Rate after the visit"
    const val RATE_VISIT_SUBTITLE = "About 30 minutes after the film ends"
    const val PERMISSION_NOTE =
        "Notifications are off for Cinexperience, so reminders can't be shown. " +
            "To get them, allow notifications for the app in Android's settings."
    const val OPEN_SETTINGS = "Open notification settings"

    fun leadTime(leadTime: ReminderLeadTime): String = if (leadTime.hours == 1) "1 hour" else "${leadTime.hours} hours"
}

/** Whether Android allows the app's notifications: the runtime permission (minSdk 34) and the app's switch in Android's settings. */
internal fun notificationsAllowed(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false

/** Android's notification settings of this app (no browser, no network). */
internal fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * Settings → Reminders (spec §6.7, §11.3): "Before the showing" with the lead time
 * chips (shown while it is on), "Rate after the visit", the note on allowing
 * notifications in Android's settings, and why the last change failed.
 */
@Composable
internal fun ReminderRows(state: ReminderSettingsState, enabled: Boolean, actions: ReminderSettingsActions) {
    val switchesEnabled = enabled && state.isLoaded
    val settings = state.settings
    SwitchRow(
        icon = CinexIcons.Bell,
        title = ReminderTexts.BEFORE_SHOWING,
        subtitle = ReminderTexts.BEFORE_SHOWING_SUBTITLE,
        checked = settings.beforeShowing,
        enabled = switchesEnabled,
        onCheckedChange = { actions.onSwitchChanged(ReminderKind.BEFORE_SHOWING, it) },
    )
    if (settings.beforeShowing) {
        Column(
            // Under the title, past the icon (16 + 22 + 14 dp).
            Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(ReminderTexts.LEAD_TIME_LABEL, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
            ChoiceChipRow {
                ReminderLeadTime.entries.forEach { leadTime ->
                    ChoiceChip(
                        text = ReminderTexts.leadTime(leadTime),
                        selected = leadTime == settings.leadTime,
                        onClick = { actions.onLeadTimeChanged(leadTime) },
                        enabled = switchesEnabled,
                    )
                }
            }
        }
    }
    RowDivider()
    SwitchRow(
        icon = CinexIcons.StarOutline,
        title = ReminderTexts.RATE_VISIT,
        subtitle = ReminderTexts.RATE_VISIT_SUBTITLE,
        checked = settings.rateAfterVisit,
        enabled = switchesEnabled,
        onCheckedChange = { actions.onSwitchChanged(ReminderKind.RATE_VISIT, it) },
    )
    if (state.showsPermissionNote) {
        RowDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(ReminderTexts.PERMISSION_NOTE, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.text)
            OutlinedPillButton(text = ReminderTexts.OPEN_SETTINGS, onClick = actions::onOpenNotificationSettings, icon = null)
        }
    }
    state.errorMessage?.let { message ->
        RowDivider()
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** A settings row that is a switch (like Settings → Program's): the row toggles, the switch only shows the state. */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val titleColor = if (enabled) CinexTheme.colors.text else CinexTheme.colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = titleColor, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = titleColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CinexTheme.colors.onPrimary,
                checkedTrackColor = CinexTheme.colors.text,
                uncheckedThumbColor = CinexTheme.colors.fieldOutline,
                uncheckedTrackColor = CinexTheme.colors.surface,
                uncheckedBorderColor = CinexTheme.colors.fieldOutline,
            ),
        )
    }
}
