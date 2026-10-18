package io.github.katznmeik.cinexperience.feature.backup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.settings.ReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → Reminders (spec §6.7, §11.3).
 *
 * @property isLoaded the stored settings have been read; until then the switches can't be used.
 * @property notificationsAllowed Android allows the app's notifications (the permission
 *   `POST_NOTIFICATIONS`), as the screen checked last (when it was shown or resumed).
 * @property permissionRefused the last request for the permission was refused.
 * @property errorMessage why the last change failed (or Android's settings didn't open).
 */
data class ReminderSettingsState(
    val isLoaded: Boolean = false,
    val settings: ReminderSettings = ReminderSettings(),
    val notificationsAllowed: Boolean = true,
    val permissionRefused: Boolean = false,
    val errorMessage: String? = null,
) {
    /**
     * The note on allowing notifications in Android's settings: after the permission
     * was refused (the switch stayed off), and while a reminder is switched on but
     * Android doesn't allow notifications (revoked later; the switch stays on, and the
     * reminders come again once they are allowed).
     */
    val showsPermissionNote: Boolean get() = !notificationsAllowed && (permissionRefused || settings.anyOn)

    companion object {
        /** Both switches off, loaded, notifications allowed. */
        val OFF: ReminderSettingsState = ReminderSettingsState(isLoaded = true)
    }
}

/**
 * The Reminders section of Settings (spec §6.7): the "Before the showing" switch with
 * its lead time and the "Rate after the visit" switch, both off until switched on.
 * Switching one on needs Android's notification permission: [onSwitchChanged] says
 * when the screen has to ask for it, and [onPermissionResult] switches it on once it
 * is granted (refused, the switch stays off and the note is shown). Changes are
 * ignored while a backup import runs ([isImporting]), like every other write.
 */
class ReminderSettingsViewModel(
    private val store: ReminderSettingsStore,
    private val isImporting: StateFlow<Boolean>,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private data class Local(
        val notificationsAllowed: Boolean = true,
        val permissionRefused: Boolean = false,
        val errorMessage: String? = null,
    )

    private val local = MutableStateFlow(Local())

    private val stored: Flow<ReminderSettings?> = store.settings
        .map<ReminderSettings, ReminderSettings?> { it }
        // Unreadable settings read as both off (the store doesn't throw; this is a backstop).
        .catch { emit(ReminderSettings()) }
        .onStart { emit(null) }

    val uiState: StateFlow<ReminderSettingsState> =
        combine(stored, local) { settings, local ->
            ReminderSettingsState(
                isLoaded = settings != null,
                settings = settings ?: ReminderSettings(),
                notificationsAllowed = local.notificationsAllowed,
                permissionRefused = local.permissionRefused,
                errorMessage = local.errorMessage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderSettingsState())

    /** The screen checked whether Android allows the app's notifications. */
    fun onNotificationsChecked(allowed: Boolean) {
        local.update {
            // Allowed again (e.g. in Android's settings): an earlier refusal no longer matters.
            if (allowed) it.copy(notificationsAllowed = true, permissionRefused = false) else it.copy(notificationsAllowed = false)
        }
    }

    /**
     * The switch of [kind] was turned [on] or off; [notificationsAllowed] is whether
     * Android allows notifications now. Returns true if the screen must ask for the
     * permission first (then [onPermissionResult]); off and an allowed on are stored at
     * once.
     */
    fun onSwitchChanged(kind: ReminderKind, on: Boolean, notificationsAllowed: Boolean): Boolean {
        if (isImporting.value) return false
        onNotificationsChecked(notificationsAllowed)
        if (on && !notificationsAllowed) {
            savedState[PENDING_KEY] = kind.code
            return true
        }
        write { it.with(kind, on) }
        return false
    }

    /**
     * The answer to the permission request of [onSwitchChanged]: [allowed] is whether
     * Android allows notifications now. Allowed, the waiting switch goes on; refused, it
     * stays off and the note explains how to allow them.
     */
    fun onPermissionResult(allowed: Boolean) {
        val kind = ReminderKind.fromCode(savedState.get<String>(PENDING_KEY))
        savedState[PENDING_KEY] = null
        if (allowed) {
            local.update { it.copy(notificationsAllowed = true, permissionRefused = false) }
            if (kind != null && !isImporting.value) write { it.with(kind, true) }
        } else {
            local.update { it.copy(notificationsAllowed = false, permissionRefused = true) }
        }
    }

    /** One of the lead time chips (1, 2 or 3 hours before the start). */
    fun onLeadTimeChanged(leadTime: ReminderLeadTime) {
        if (isImporting.value) return
        write { it.copy(leadTime = leadTime) }
    }

    /** Android's notification settings couldn't be opened (no app handles them). */
    fun onNotificationSettingsUnavailable() {
        local.update { it.copy(errorMessage = SETTINGS_UNAVAILABLE) }
    }

    private fun write(change: (ReminderSettings) -> ReminderSettings) {
        local.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            val stored = store.update(change)
            if (!stored) local.update { it.copy(errorMessage = NOT_SAVED) }
        }
    }

    companion object {
        const val NOT_SAVED: String = "The setting couldn't be saved on the phone. Try again."
        const val SETTINGS_UNAVAILABLE: String = "Android's notification settings couldn't be opened."
        private const val PENDING_KEY = "reminders.pendingPermission"

        fun factory(store: ReminderSettingsStore, isImporting: StateFlow<Boolean>): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReminderSettingsViewModel(store, isImporting, createSavedStateHandle()) }
        }
    }
}
