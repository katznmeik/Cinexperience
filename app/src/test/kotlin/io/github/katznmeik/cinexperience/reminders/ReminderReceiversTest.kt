package io.github.katznmeik.cinexperience.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import io.github.katznmeik.cinexperience.TestCinexApplication
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.MutableClock
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.ui.FakeAppContainer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The receivers (spec §11.3): a restart, an app update and a time zone change schedule
 * the reminders again (sent as the system sends them, through the manifest's filter);
 * any other action is ignored. When an alarm fires, the reminder is checked again
 * (deleted visit, changed start, rated, setting off, notifications not allowed, too
 * late) and posted only if it is still due. The invented visit ([SampleTickets]) is on
 * Saturday 17 Oct 2026 at 20:15 (18:15 UTC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestCinexApplication::class)
class ReminderReceiversTest {

    private val context = RuntimeEnvironment.getApplication()
    private val repository = FakeCinexRepository(SampleTickets.store())
    private val settings = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true, rateAfterVisit = true))
    private val clock = MutableClock(Instant.parse("2026-10-17T16:16:00Z")) // 1 min after the 2 h reminder
    private val scheduler = RecordingReminderScheduler()
    private val container = FakeAppContainer(repository, clock = clock, reminderSettingsStore = settings, reminderScheduler = scheduler)
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private val visit = SampleTickets.VISIT
    private val showtime = PlannedReminder(
        visit,
        ReminderKind.BEFORE_SHOWING,
        Instant.parse("2026-10-17T16:15:00Z"),
        LocalDate.of(2026, 10, 17),
        SampleTickets.START,
        ReminderLeadTime.TWO_HOURS,
    )
    private val rate = PlannedReminder(visit, ReminderKind.RATE_VISIT, Instant.parse("2026-10-17T21:15:00Z"), showtime.date, SampleTickets.START)

    @Before
    fun setUp() {
        TestCinexApplication.next = container
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        TestCinexApplication.next = null
    }

    private fun broadcast(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fire(reminder: PlannedReminder) = broadcast(ReminderAlarms.intent(context, reminder))

    private fun posted(kind: ReminderKind = ReminderKind.BEFORE_SHOWING) = notifications.getNotification(ReminderNotifier.tag(visit), ReminderNotifier.id(kind))

    @Test
    fun aRestartAnUpdateAndATimeZoneChangeScheduleAgain() {
        for ((runs, action) in ReminderRestartReceiver.ACTIONS.withIndex()) {
            broadcast(Intent(action))
            assertEquals(action, runs + 1, scheduler.plans.size)
            // At 18:16 Berlin the 2 h reminder has passed: only Rate your visit is left.
            assertEquals(action, listOf(rate), scheduler.last)
        }
        assertEquals(setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED), ReminderRestartReceiver.ACTIONS)
    }

    @Test
    fun anyOtherActionIsIgnored() {
        // Explicit intents reach an exported receiver whatever their action.
        for (action in listOf("com.example.RESCHEDULE", Intent.ACTION_TIME_CHANGED, ReminderAlarms.ACTION)) {
            ReminderRestartReceiver().onReceive(context, Intent(action))
        }
        ReminderRestartReceiver().onReceive(context, Intent())
        // The alarm receiver takes only a complete reminder or a check-in of its own actions.
        ReminderAlarmReceiver().onReceive(context, ReminderAlarms.intent(context, showtime).setAction(Intent.ACTION_BOOT_COMPLETED))
        ReminderAlarmReceiver().onReceive(context, ReminderAlarms.baseIntent(context, visit, ReminderKind.BEFORE_SHOWING))
        ReminderAlarmReceiver().onReceive(context, Intent("com.example.CHECK_IN"))
        assertTrue(scheduler.plans.isEmpty())
        assertEquals(0, notifications.size())
    }

    @Test
    fun aCheckInOnlyPlansAgain() {
        clock.now = Instant.parse("2026-10-17T15:00:00Z")
        broadcast(ReminderAlarms.checkInIntent(context))
        assertEquals(1, scheduler.plans.size)
        assertEquals(listOf(showtime, rate), scheduler.last)
        assertEquals(0, notifications.size())
    }

    @Test
    fun anAlarmDeliveredASecondEarlyIsPostedAndMarkedBeforeThePlan() {
        clock.now = showtime.at.minusSeconds(1)
        fire(showtime)
        assertTrue(posted() != null)
        // Marked as posted, so the plan right after (which still has it) doesn't set it again
        // (AlarmReminderSchedulerTest, ReminderAlarmsThroughTheReceiversTest).
        assertEquals(listOf(showtime), scheduler.posted)
        assertEquals(listOf(showtime, rate), scheduler.last)
    }

    @Test
    fun aRestartWhosePlanFailsSetsARetryCheckIn() {
        repository.observeFailure = java.io.IOException("database")
        broadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(scheduler.plans.isEmpty())
        assertEquals(listOf(clock.now.plusSeconds(15 * 60)), scheduler.retries)
        // An alarm whose plan fails too.
        fire(showtime)
        assertEquals(2, scheduler.retries.size)
    }

    @Test
    fun aDueReminderIsPostedAndTheNextOnesScheduled() {
        fire(showtime)
        assertTrue(posted() != null)
        assertEquals("then scheduled again", listOf(rate), scheduler.last)
        assertTrue(scheduler.retries.isEmpty())
        clock.now = Instant.parse("2026-10-17T21:20:00Z")
        fire(rate)
        assertTrue(posted(ReminderKind.RATE_VISIT) != null)
        assertEquals(listOf(showtime, rate), scheduler.posted)
    }

    @Test
    fun aDeletedVisitGetsNoReminder() {
        runBlocking { repository.deleteVisit(visit) }
        fire(showtime)
        assertEquals(0, notifications.size())
        assertTrue("not marked as posted", scheduler.posted.isEmpty())
        assertEquals("but planned again", 1, scheduler.plans.size)
    }

    @Test
    fun aChangedStartGetsNoReminderFromTheOldAlarm() {
        runBlocking { repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(20, 45))) }
        fire(showtime)
        assertEquals(0, notifications.size())
        // Its new alarm was set meanwhile (here by the run after the alarm).
        assertEquals(listOf(Instant.parse("2026-10-17T16:45:00Z"), Instant.parse("2026-10-17T21:45:00Z")), scheduler.last!!.map { it.at })
    }

    @Test
    fun aRatedVisitGetsNoRateReminder() {
        runBlocking { repository.saveVisit(repository.getVisit(visit)!!.copy(seatRating = Rating(5))) }
        clock.now = Instant.parse("2026-10-17T21:20:00Z")
        fire(rate)
        assertNull(posted(ReminderKind.RATE_VISIT))
    }

    @Test
    fun aReminderSwitchedOffIsNotPosted() {
        settings.current.update { it.copy(beforeShowing = false) }
        fire(showtime)
        settings.current.update { it.copy(beforeShowing = true, leadTime = ReminderLeadTime.ONE_HOUR) }
        fire(showtime) // the 2 h alarm, after the lead time changed to 1 h
        assertEquals(0, notifications.size())
    }

    @Test
    fun nothingIsPostedWithoutThePermission() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        fire(showtime)
        assertEquals(0, notifications.size())
        // Allowed, but the app's notifications switched off in Android's settings.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.setNotificationsEnabled(false)
        fire(showtime)
        assertEquals(0, notifications.size())
    }

    @Test
    fun theShowtimeReminderIsTooLateOnceTheShowingHasStarted() {
        clock.now = Instant.parse("2026-10-17T18:15:00Z")
        fire(showtime)
        assertEquals(0, notifications.size())
    }
}
