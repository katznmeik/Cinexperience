package io.github.katznmeik.cinexperience.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.TestCinexApplication
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.MutableClock
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.ui.FakeAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime

/** (action, trigger) of every alarm set in [alarmManager], soonest first: "own before", "own rate" or "check-in". */
private fun alarmsOf(alarmManager: AlarmManager): List<Pair<String, Instant>> =
    shadowOf(alarmManager).scheduledAlarms.sortedBy { it.triggerAtMs }.map { alarm ->
        val intent = shadowOf(alarm.operation).savedIntent
        val name = ReminderAlarms.reminderOf(intent)?.let { "own ${it.kind.code}" } ?: "check-in"
        name to Instant.ofEpochMilli(alarm.triggerAtMs)
    }

/**
 * The coordinator with the real alarms (spec §11.3 rules 4 and 5): what is scheduled
 * shows up in AlarmManager, and a deleted or changed visit, a reminder switched off and
 * a backup import act on the alarms themselves. Invented visit ([SampleTickets]),
 * Saturday 17 Oct 2026 at 20:15 (18:15 UTC): the showtime reminder at 16:15 UTC, Rate
 * your visit at 21:15 UTC.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderAlarmsEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val repository = FakeCinexRepository(SampleTickets.store())
    private val settings = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true, rateAfterVisit = true))
    private val importing = MutableStateFlow(false)
    private val clock = MutableClock(SampleProgram.FETCHED_AT)
    private val coordinator = ReminderCoordinator(
        repository,
        repository,
        FakeProgramRepository(),
        settings,
        importing,
        AlarmReminderScheduler(context),
        clock,
    )

    private val showtime = Instant.parse("2026-10-17T16:15:00Z")
    private val closeBy = showtime.minusSeconds(9 * 60)

    private fun alarms() = alarmsOf(alarmManager)

    @Test
    fun theAlarmsFollowTheVisitsAndTheSettings() = runTest {
        // Two days ahead: one check-in, 65 minutes before the soonest reminder.
        assertTrue(coordinator.rescheduleNow())
        assertEquals(listOf("check-in" to showtime.minusSeconds(65 * 60)), alarms())

        // 9 minutes before the showtime reminder: its own alarm, and a check-in for Rate your visit.
        clock.now = closeBy
        assertTrue(coordinator.rescheduleNow())
        assertEquals(listOf("own before", "check-in"), alarms().map { it.first })
        assertEquals(showtime, alarms().first().second)

        // The start moved to 20:20: the reminder is 14 minutes away now, so its own alarm goes for a check-in.
        repository.saveVisit(repository.getVisit(SampleTickets.VISIT)!!.copy(startTime = LocalTime.of(20, 20)))
        assertTrue(coordinator.rescheduleNow())
        assertEquals(listOf("check-in"), alarms().map { it.first })

        // "Before the showing" switched off, then the visit deleted: no alarm is left.
        settings.current.update { it.copy(beforeShowing = false) }
        assertTrue(coordinator.rescheduleNow())
        assertEquals(listOf("check-in"), alarms().map { it.first })
        repository.deleteVisit(SampleTickets.VISIT)
        assertTrue(coordinator.rescheduleNow())
        assertEquals(emptyList<Pair<String, Instant>>(), alarms())
    }

    @Test
    fun aBackupImportLeavesTheAlarmsUntilItHasEnded() = runTest {
        clock.now = closeBy
        assertTrue(coordinator.rescheduleNow())
        importing.value = true
        repository.deleteVisit(SampleTickets.VISIT)
        assertFalse(coordinator.rescheduleNow())
        assertEquals(2, alarms().size)
        importing.value = false
        assertTrue(coordinator.rescheduleNow())
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun bothSwitchedOffCancelEveryAlarm() = runTest {
        clock.now = closeBy
        assertTrue(coordinator.rescheduleNow())
        settings.current.value = ReminderSettings()
        assertTrue(coordinator.rescheduleNow())
        assertTrue(alarms().isEmpty())
    }
}

/**
 * The chain through the receivers (spec §11.3): from two days ahead, each check-in alarm
 * fires (on time), plans again and sets the next, until the reminder's own alarm, which
 * posts the notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestCinexApplication::class)
class ReminderChainThroughTheReceiversTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val clock = MutableClock(SampleProgram.FETCHED_AT)

    @After
    fun tearDown() {
        TestCinexApplication.next = null
    }

    @Test
    fun eachCheckInSetsTheNextUntilTheReminderIsPosted() = runTest {
        TestCinexApplication.next = FakeAppContainer(
            FakeCinexRepository(SampleTickets.store()),
            clock = clock,
            reminderSettingsStore = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true)),
            reminderScheduler = AlarmReminderScheduler(context),
        )
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val reminder = Instant.parse("2026-10-17T16:15:00Z")
        assertTrue(TestCinexApplication.next!!.reminders.coordinator.rescheduleNow())

        val minutesBefore = ArrayList<Long>()
        while (minutesBefore.size < 10) {
            val (name, trigger) = alarmsOf(alarmManager).single()
            minutesBefore += java.time.Duration.between(trigger, reminder).toMinutes()
            // The alarm fires on time.
            clock.now = trigger
            val alarm = shadowOf(alarmManager).scheduledAlarms.single()
            shadowOf(alarmManager).fireAlarm(alarm)
            shadowOf(Looper.getMainLooper()).idle()
            if (name == "own before") break
        }
        assertEquals(listOf(65L, 30, 16, 9, 0), minutesBefore)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNotNull(notifications.getNotification(ReminderNotifier.tag(SampleTickets.VISIT), ReminderNotifier.id(ReminderKind.BEFORE_SHOWING)))
        assertTrue("nothing left to set", alarmsOf(alarmManager).isEmpty())
    }
}
