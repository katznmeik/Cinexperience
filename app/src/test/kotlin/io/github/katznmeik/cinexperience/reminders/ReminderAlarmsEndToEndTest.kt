package io.github.katznmeik.cinexperience.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.TestCinexApplication
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderSchedule
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MutableClock
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import io.github.katznmeik.cinexperience.ui.FakeAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * (name, trigger) of every alarm set in [alarmManager], soonest first: "own before",
 * "own rate" or "check-in"; each must be an exact alarm that fires while idle.
 */
private fun alarmsOf(alarmManager: AlarmManager): List<Pair<String, Instant>> =
    shadowOf(alarmManager).scheduledAlarms.sortedBy { it.triggerAtMs }.map { alarm ->
        assertEquals("exact", ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertTrue("allowed while idle", alarm.isAllowWhileIdle)
        val intent = shadowOf(alarm.operation).savedIntent
        val name = ReminderAlarms.reminderOf(intent)?.let { "own ${it.kind.code}" } ?: "check-in"
        name to Instant.ofEpochMilli(alarm.triggerAtMs)
    }

/**
 * The coordinator with the real alarms (spec §11.3 rules 4 and 5): what is scheduled
 * shows up in AlarmManager as exact alarms at the reminders' times, and a changed or
 * deleted visit, a reminder switched off and a backup import act on the alarms
 * themselves. Invented visit ([SampleTickets]), Saturday 17 Oct 2026 at 20:15 (18:15
 * UTC): the showtime reminder at 16:15 UTC, Rate your visit at 21:15 UTC.
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
    private val rate = Instant.parse("2026-10-17T21:15:00Z")

    private fun alarms() = alarmsOf(alarmManager)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun theAlarmsFollowTheVisitsAndTheSettings() = runTest {
        // Two days ahead: both reminders have their exact alarm at their time already.
        assertTrue(coordinator.rescheduleNow())
        assertEquals(listOf("own before" to showtime, "own rate" to rate), alarms())

        // The start moved to 20:20: both alarms move with it.
        repository.saveVisit(repository.getVisit(SampleTickets.VISIT)!!.copy(startTime = LocalTime.of(20, 20)))
        assertTrue(coordinator.rescheduleNow())
        val later = Duration.ofMinutes(5)
        assertEquals(listOf("own before" to showtime.plus(later), "own rate" to rate.plus(later)), alarms())

        // "Before the showing" switched off, then the visit deleted: no alarm is left.
        settings.current.update { it.copy(beforeShowing = false) }
        assertTrue(coordinator.rescheduleNow())
        assertEquals(listOf("own rate" to rate.plus(later)), alarms())
        repository.deleteVisit(SampleTickets.VISIT)
        assertTrue(coordinator.rescheduleNow())
        assertEquals(emptyList<Pair<String, Instant>>(), alarms())
    }

    @Test
    fun aBackupImportLeavesTheAlarmsUntilItHasEnded() = runTest {
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
        assertTrue(coordinator.rescheduleNow())
        settings.current.value = ReminderSettings()
        assertTrue(coordinator.rescheduleNow())
        assertTrue(alarms().isEmpty())
    }
}

/**
 * The alarms through the receivers (spec §11.3): planned a week ahead, with nothing in
 * between (the app isn't opened), each reminder's own alarm fires at its time and posts
 * the notification; an alarm delivered a moment early, delivered again, or a "Rate your
 * visit" whose time changes after it was posted posts once and isn't set again; the
 * reminders beyond the plan's limit follow as the alarms fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestCinexApplication::class)
class ReminderAlarmsThroughTheReceiversTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val notifications get() = shadowOf(notificationManager)
    private val weekAhead = Instant.parse("2026-10-10T08:00:00Z")
    private val clock = MutableClock(weekAhead)
    private val showtime = Instant.parse("2026-10-17T16:15:00Z")
    private val rate = Instant.parse("2026-10-17T21:15:00Z")

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        TestCinexApplication.next = null
    }

    private fun container(
        store: FakeStore = SampleTickets.store(now = weekAhead, daysAhead = 7),
        program: FakeProgramRepository = FakeProgramRepository(),
    ) = FakeAppContainer(
        FakeCinexRepository(store),
        programRepository = program,
        clock = clock,
        reminderSettingsStore = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true, rateAfterVisit = true)),
        reminderScheduler = AlarmReminderScheduler(context),
    ).also { TestCinexApplication.next = it }

    private fun alarms() = alarmsOf(alarmManager)

    /** Android delivers the alarm set for [trigger] at [now]; its intent. */
    private fun fire(trigger: Instant, now: Instant = trigger): Intent {
        clock.now = now
        val alarm = shadowOf(alarmManager).scheduledAlarms.single { it.triggerAtMs == trigger.toEpochMilli() }
        val intent = shadowOf(alarm.operation).savedIntent
        shadowOf(alarmManager).fireAlarm(alarm)
        shadowOf(Looper.getMainLooper()).idle()
        return intent
    }

    private fun dismiss(kind: ReminderKind) = notificationManager.cancel(ReminderNotifier.tag(SampleTickets.VISIT), ReminderNotifier.id(kind))

    private fun posted(kind: ReminderKind, visitId: Long = SampleTickets.VISIT) =
        notifications.getNotification(ReminderNotifier.tag(visitId), ReminderNotifier.id(kind))

    @Test
    fun eachReminderIsPostedByItsOwnAlarmAWeekLater() = runTest {
        assertTrue(container().reminders.coordinator.rescheduleNow())
        assertEquals(listOf("own before" to showtime, "own rate" to rate), alarms())

        fire(showtime)
        assertNotNull(posted(ReminderKind.BEFORE_SHOWING))
        assertEquals(listOf("own rate" to rate), alarms())

        fire(rate)
        assertNotNull(posted(ReminderKind.RATE_VISIT))
        assertTrue("nothing left to set", alarms().isEmpty())
    }

    @Test
    fun anAlarmDeliveredASecondEarlyIsPostedOnceAndNotSetAgain() = runTest {
        val coordinator = container().reminders.coordinator
        assertTrue(coordinator.rescheduleNow())

        val delivered = fire(showtime, now = showtime.minusSeconds(1))
        assertNotNull(posted(ReminderKind.BEFORE_SHOWING))
        // The plan right after still has the reminder (its time is a second ahead), but sets nothing for it.
        assertEquals(listOf("own rate" to rate), alarms())

        // The owner swipes it away. The same alarm delivered again (e.g. set again by a plan that
        // ran between its delivery and its mark): nothing is posted.
        dismiss(ReminderKind.BEFORE_SHOWING)
        context.sendBroadcast(delivered)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(posted(ReminderKind.BEFORE_SHOWING))

        // The app opens and plans again, before and after its time: it doesn't come back.
        for (now in listOf(showtime.minusMillis(500), showtime, showtime.plusSeconds(60))) {
            clock.now = now
            assertTrue(coordinator.rescheduleNow())
            assertEquals("$now", listOf("own rate" to rate), alarms())
        }
        assertNull(posted(ReminderKind.BEFORE_SHOWING))
        assertEquals(0, notifications.size())
    }

    @Test
    fun rateYourVisitIsNotPostedAgainWhenItsTimeChangesAfterwards() = runTest {
        // The program has the showing with a runtime of 100 minutes: Rate your visit at 20:25 UTC
        // (20:15 Berlin + 100 min + 30 min).
        val program = FakeProgramRepository()
        program.state.update {
            it.copy(program = buildProgram { film(SampleTickets.FILM, runtimeMinutes = 100) { showing("2026-10-17T20:15", "delphi LUX") } })
        }
        val coordinator = container(program = program).reminders.coordinator
        assertTrue(coordinator.rescheduleNow())
        val rateWithRuntime = Instant.parse("2026-10-17T20:25:00Z")
        assertEquals(listOf("own before" to showtime, "own rate" to rateWithRuntime), alarms())
        fire(showtime)
        fire(rateWithRuntime)
        assertNotNull(posted(ReminderKind.RATE_VISIT))
        assertTrue(alarms().isEmpty())

        // The owner dismisses it; then the runtime is unknown (the program deleted): the plan has
        // Rate your visit at 21:15 UTC (+ 2.5 h + 30 min) now, but it was posted for this showing.
        dismiss(ReminderKind.RATE_VISIT)
        program.state.update { it.copy(program = null) }
        clock.now = rateWithRuntime.plusSeconds(60)
        assertTrue(coordinator.rescheduleNow())
        assertEquals(emptyList<Pair<String, Instant>>(), alarms())
        clock.now = rate.minusSeconds(60)
        assertTrue(coordinator.rescheduleNow())
        assertTrue(alarms().isEmpty())
        assertNull(posted(ReminderKind.RATE_VISIT))
    }

    @Test
    fun theRemindersBeyondThePlansLimitFollowAsTheAlarmsFire() = runTest {
        // 26 visits on the following days, two reminders each: 52, of which the soonest 50 are set.
        val visits = (0 until 26).map { day ->
            SampleTickets.visit(SampleTickets.dateAfter(weekAhead, 1L + day), id = SampleTickets.VISIT + day)
        }
        assertTrue(container(SampleTickets.store(visits = visits)).reminders.coordinator.rescheduleNow())
        val first = alarms()
        assertEquals(ReminderSchedule.MAX_SCHEDULED, first.size)

        // The first fires and is posted; the plan after it sets the next one.
        fire(first.first().second)
        assertNotNull(posted(ReminderKind.BEFORE_SHOWING, SampleTickets.VISIT))
        val next = alarms()
        assertEquals(ReminderSchedule.MAX_SCHEDULED, next.size)
        assertEquals(first.drop(1), next.dropLast(1))
        assertEquals("the last day's showtime reminder", "own before", next.last().first)
    }
}
