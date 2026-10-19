package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.data.settings.ReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The reminder settings in memory (spec §6.7). Set [failWrites] to make every write
 * fail like a full disk (nothing changes, `update` returns false).
 */
class FakeReminderSettingsStore(initial: ReminderSettings = ReminderSettings()) : ReminderSettingsStore {

    val current: MutableStateFlow<ReminderSettings> = MutableStateFlow(initial)

    var failWrites: Boolean = false

    /** How many writes changed something. */
    var writes: Int = 0
        private set

    override val settings: Flow<ReminderSettings> get() = current

    override suspend fun update(change: (ReminderSettings) -> ReminderSettings): Boolean {
        val after = change(current.value)
        if (after == current.value) return true
        if (failWrites) return false
        current.value = after
        writes++
        return true
    }
}
