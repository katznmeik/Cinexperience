package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.settings.SharedPreferencesReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reminder settings in `reminder_settings.xml` (spec §6.7, §8): both off until
 * switched on, every change reaches the flow, damaged values keep their defaults and
 * a failed write changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderSettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SharedPreferencesReminderSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun store() = SharedPreferencesReminderSettingsStore(context, Dispatchers.Unconfined)

    @Test
    fun bothAreOffUntilSwitchedOn() = runTest {
        assertEquals(ReminderSettings(), store().settings.first())
        assertEquals(ReminderSettings(beforeShowing = false, leadTime = ReminderLeadTime.TWO_HOURS, rateAfterVisit = false), store().settings.first())
    }

    @Test
    fun changesAreStoredAndReachTheFlow() = runTest {
        val store = store()
        val seen = async { store.settings.take(3).toList() }
        yield()
        assertTrue(store.update { it.with(ReminderKind.BEFORE_SHOWING, true) })
        yield()
        assertTrue(store.update { it.copy(leadTime = ReminderLeadTime.ONE_HOUR) })
        assertEquals(
            listOf(
                ReminderSettings(),
                ReminderSettings(beforeShowing = true),
                ReminderSettings(beforeShowing = true, leadTime = ReminderLeadTime.ONE_HOUR),
            ),
            seen.await(),
        )
        // A new store (the next process) reads them back.
        assertEquals(ReminderSettings(beforeShowing = true, leadTime = ReminderLeadTime.ONE_HOUR), store().settings.first())
        assertTrue(store().update { it.with(ReminderKind.RATE_VISIT, true).with(ReminderKind.BEFORE_SHOWING, false) })
        assertEquals(ReminderSettings(leadTime = ReminderLeadTime.ONE_HOUR, rateAfterVisit = true), store().settings.first())
    }

    @Test
    fun damagedValuesKeepTheirDefaults() = runTest {
        prefs.edit().putString("before_showing", "yes").putInt("lead_hours", 7).putBoolean("rate_after_visit", true).commit()
        assertEquals(ReminderSettings(rateAfterVisit = true), store().settings.first())
        prefs.edit().clear().putBoolean("before_showing", true).putString("lead_hours", "3").commit()
        assertEquals(ReminderSettings(beforeShowing = true), store().settings.first())
        prefs.edit().clear().putInt("lead_hours", 3).commit()
        assertEquals(ReminderSettings(leadTime = ReminderLeadTime.THREE_HOURS), store().settings.first())
    }

    @Test
    fun aFailedWriteChangesNothing() = runTest {
        val failing = SharedPreferencesReminderSettingsStore({ throw IllegalStateException("no storage") }, Dispatchers.Unconfined)
        assertEquals(ReminderSettings(), failing.settings.first())
        assertFalse(failing.update { it.with(ReminderKind.RATE_VISIT, true) })
        assertEquals(ReminderSettings(), failing.settings.first())
        // An unchanged value needs no write.
        assertTrue(failing.update { it })
    }
}
