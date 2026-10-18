package io.github.katznmeik.cinexperience.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The coordinator with the real alarms (spec §11.3 rules 4 and 5): what is scheduled
 * shows up in AlarmManager, and a deleted or changed visit, a reminder switched off and
 * a backup import act on the alarms themselves. Invented visit ([SampleTickets]),
 * Saturday 17 Oct 2026 at 20:15 (18:15 UTC); the clock stands at 15 Oct, 10:00 Berlin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderAlarmsEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val repository = FakeCinexRepository(SampleTickets.store())
    private val settings = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true, rateAfterVisit = true))
    private val importing = MutableStateFlow(false)
    private val coordinator = ReminderCoordinator(
        repository,
        repository,
        FakeProgramRepository(),
        settings,
        importing,
        AlarmReminderScheduler(context),
        Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC),
    )

    /** (kind, time) of every alarm set, soonest first. */
    private fun alarmed(): List<Pair<ReminderKind, Instant>> = alarms.scheduledAlarms.sortedBy { it.triggerAtMs }.map {
        val reminder = checkNotNull(ReminderAlarms.reminderOf(shadowOf(it.operation).savedIntent))
        reminder.kind to Instant.ofEpochMilli(it.triggerAtMs)
    }

    @Test
    fun theAlarmsFollowTheVisitsAndTheSettings() = runTest {
        assertTrue(coordinator.rescheduleNow())
        assertEquals(
            listOf(ReminderKind.BEFORE_SHOWING to Instant.parse("2026-10-17T16:15:00Z"), ReminderKind.RATE_VISIT to Instant.parse("2026-10-17T21:15:00Z")),
            alarmed(),
        )

        // The start moved to 21:00: both alarms move.
        repository.saveVisit(repository.getVisit(SampleTickets.VISIT)!!.copy(startTime = LocalTime.of(21, 0)))
        assertTrue(coordinator.rescheduleNow())
        assertEquals(
            listOf(ReminderKind.BEFORE_SHOWING to Instant.parse("2026-10-17T17:00:00Z"), ReminderKind.RATE_VISIT to Instant.parse("2026-10-17T22:00:00Z")),
            alarmed(),
        )

        // "Before the showing" switched off: its alarm is cancelled, the other stays.
        settings.current.update { it.copy(beforeShowing = false) }
        assertTrue(coordinator.rescheduleNow())
        assertEquals(listOf(ReminderKind.RATE_VISIT), alarmed().map { it.first })

        // The visit deleted: no alarm is left.
        repository.deleteVisit(SampleTickets.VISIT)
        assertTrue(coordinator.rescheduleNow())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun aBackupImportLeavesTheAlarmsUntilItHasEnded() = runTest {
        assertTrue(coordinator.rescheduleNow())
        importing.value = true
        repository.deleteVisit(SampleTickets.VISIT)
        assertFalse(coordinator.rescheduleNow())
        assertEquals(2, alarms.scheduledAlarms.size)
        importing.value = false
        assertTrue(coordinator.rescheduleNow())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun bothSwitchedOffCancelEveryAlarm() = runTest {
        assertTrue(coordinator.rescheduleNow())
        settings.current.value = ReminderSettings()
        assertTrue(coordinator.rescheduleNow())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
