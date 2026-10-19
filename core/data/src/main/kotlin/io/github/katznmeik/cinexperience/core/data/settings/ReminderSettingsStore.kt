package io.github.katznmeik.cinexperience.core.data.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The reminder settings (spec §6.7, §11.3): Settings writes them, the app's reminder
 * scheduling reads them. They are app settings (spec §8): covered by Auto Backup,
 * not part of the backup file. Implementations never throw: unreadable settings read
 * as the defaults (both off), and a failed write returns false and changes nothing.
 */
interface ReminderSettingsStore {
    /** The current settings, then every change. */
    val settings: Flow<ReminderSettings>

    /** Stores [change] applied to the current settings, one change at a time; false if it couldn't be stored. */
    suspend fun update(change: (ReminderSettings) -> ReminderSettings): Boolean
}

/**
 * [ReminderSettingsStore] in shared preferences (`reminder_settings.xml`). Each value
 * is read on its own: a missing or wrong one (another type, a lead time other than
 * 1, 2 or 3 hours) keeps its default. Reads and writes run on [ioDispatcher]; the
 * settings are read once, on first use, and then kept in memory (the app has one
 * store per process).
 */
class SharedPreferencesReminderSettingsStore(
    private val openPreferences: () -> SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReminderSettingsStore {

    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        { context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) },
        ioDispatcher,
    )

    private val prefs: SharedPreferences by lazy(openPreferences)

    private val mutex = Mutex()

    /** `null` until read. */
    private val current = MutableStateFlow<ReminderSettings?>(null)

    override val settings: Flow<ReminderSettings> = flow {
        if (current.value == null) mutex.withLock { load() }
        emitAll(current.filterNotNull())
    }.distinctUntilChanged()

    override suspend fun update(change: (ReminderSettings) -> ReminderSettings): Boolean = mutex.withLock {
        val before = load()
        val after = change(before)
        if (after == before) return@withLock true
        val stored = withContext(ioDispatcher) {
            try {
                // commit (on the IO dispatcher): its result tells whether the settings were stored,
                // which the KTX edit { } doesn't return.
                @SuppressLint("UseKtx")
                val editor = prefs.edit()
                editor.putBoolean(KEY_BEFORE_SHOWING, after.beforeShowing)
                editor.putInt(KEY_LEAD_HOURS, after.leadTime.hours)
                editor.putBoolean(KEY_RATE_AFTER_VISIT, after.rateAfterVisit)
                val committed = editor.commit()
                committed
            } catch (e: Exception) {
                false
            }
        }
        if (stored) current.value = after
        stored
    }

    /** The settings in memory, read first if needed. Called under [mutex]. */
    private suspend fun load(): ReminderSettings = current.value ?: withContext(ioDispatcher) { read() }.also { current.value = it }

    private fun read(): ReminderSettings {
        val defaults = ReminderSettings()
        fun <T> value(read: () -> T, default: T): T = try {
            read()
        } catch (e: Exception) {
            // Another type under the key (getBoolean / getInt throw), or the file can't be read.
            default
        }
        return ReminderSettings(
            beforeShowing = value({ prefs.getBoolean(KEY_BEFORE_SHOWING, defaults.beforeShowing) }, defaults.beforeShowing),
            leadTime = value({ ReminderLeadTime.ofHours(prefs.getInt(KEY_LEAD_HOURS, defaults.leadTime.hours)) }, null) ?: defaults.leadTime,
            rateAfterVisit = value({ prefs.getBoolean(KEY_RATE_AFTER_VISIT, defaults.rateAfterVisit) }, defaults.rateAfterVisit),
        )
    }

    companion object {
        const val PREFERENCES_NAME: String = "reminder_settings"
        internal const val KEY_BEFORE_SHOWING = "before_showing"
        internal const val KEY_LEAD_HOURS = "lead_hours"
        internal const val KEY_RATE_AFTER_VISIT = "rate_after_visit"
    }
}
