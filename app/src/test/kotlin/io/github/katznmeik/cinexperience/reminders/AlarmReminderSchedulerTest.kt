package io.github.katznmeik.cinexperience.reminders

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import org.junit.Assert.assertEquals
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
import java.time.LocalDate
import java.time.LocalTime

/**
 * The reminders' alarms (spec §11.3 rules 4 and 5): one exact alarm per reminder at its
 * time that fires while idle (`setExactAndAllowWhileIdle`, `RTC_WAKEUP`; normal ones if
 * Android refuses exact alarms), immutable broadcasts to the app's own receiver. A new
 * plan replaces and cancels, never an alarm whose time has passed (it may be on its
 * way); a posted reminder isn't set again; the alarms set outlive the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlarmReminderSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val alarms: ShadowAlarmManager = shadowOf(alarmManager)

    private val date = LocalDate.of(2026, 10, 17)
    private val start = LocalTime.of(20, 15)
    private val at = Instant.parse("2026-10-17T16:15:00Z") // 2 h before 20:15 Berlin

    private fun showtime(visitId: Long, at: Instant = this.at) =
        PlannedReminder(visitId, ReminderKind.BEFORE_SHOWING, at, date, start, ReminderLeadTime.TWO_HOURS)

    private fun rate(visitId: Long, at: Instant = this.at.plus(Duration.ofHours(5))) =
        PlannedReminder(visitId, ReminderKind.RATE_VISIT, at, date, start)

    private fun min(minutes: Long): Duration = Duration.ofMinutes(minutes)

    @Before
    fun setUp() {
        // USE_EXACT_ALARM is granted at install; Robolectric's default is false.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    /** The reminders with an alarm, soonest first. */
    private fun own(): List<PlannedReminder> = alarms.scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == ReminderAlarms.ACTION }
        .sortedBy { it.triggerAtMs }
        .map { checkNotNull(ReminderAlarms.reminderOf(shadowOf(it.operation).savedIntent)) }

    /** The retry check-in's time, `null` without one. */
    private fun checkIn(): Instant? = alarms.scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == ReminderAlarms.ACTION_CHECK_IN }
        .also { assertTrue("one check-in at most", it.size <= 1) }
        .singleOrNull()?.let { Instant.ofEpochMilli(it.triggerAtMs) }

    @Test
    fun eachReminderGetsAnExactAlarmAtItsTimeThatFiresWhileIdle() {
        // Days ahead: each reminder has its own alarm already, nothing in between wakes the phone.
        val soon = showtime(1)
        val later = rate(2, at.plus(Duration.ofDays(3)))
        AlarmReminderScheduler(context).schedule(listOf(later, soon), at.minus(Duration.ofDays(4)))

        assertEquals(2, alarms.scheduledAlarms.size)
        for (alarm in alarms.scheduledAlarms) {
            assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
            assertTrue("allowed while idle", alarm.isAllowWhileIdle)
            // setExactAndAllowWhileIdle: no window, not Android's heuristic one.
            assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
            val operation = shadowOf(alarm.operation)
            assertTrue(operation.isBroadcast)
            assertTrue("immutable", operation.isImmutable)
            assertEquals(ComponentName(context, ReminderAlarmReceiver::class.java), operation.savedIntent.component)
        }
        assertEquals(listOf(soon, later), own())
        assertEquals(listOf(soon.at, later.at).map { it.toEpochMilli() }, alarms.scheduledAlarms.map { it.triggerAtMs }.sorted())
        assertNull(checkIn())
    }

    @Test
    fun withoutExactAlarmsTheRemindersGetNormalOnes() {
        // Should Android ever refuse exact alarms: normal ones that fire while idle, at the same times.
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler = AlarmReminderScheduler(context)
        val now = at.minus(Duration.ofDays(2))
        scheduler.schedule(listOf(showtime(1), rate(1)), now)
        scheduler.scheduleRetry(now.plus(min(15)), now)

        assertEquals(3, alarms.scheduledAlarms.size)
        for (alarm in alarms.scheduledAlarms) {
            assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
            assertTrue("allowed while idle", alarm.isAllowWhileIdle)
            assertEquals("setAndAllowWhileIdle", ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
        }
        assertEquals(listOf(showtime(1), rate(1)), own())
        assertEquals(now.plus(min(15)), checkIn())
        // And they are replaced and cancelled like exact ones.
        scheduler.schedule(listOf(rate(1)), now)
        assertEquals(listOf(rate(1)), own())
        assertNull(checkIn())
    }

    @Test
    fun aNewPlanReplacesTheAlarmsAndCancelsTheOthersStillAhead() {
        val scheduler = AlarmReminderScheduler(context)
        val now = at.minus(Duration.ofHours(3))
        scheduler.schedule(listOf(showtime(1), showtime(2, at.plus(min(1))), rate(3)), now)
        assertEquals(3, alarms.scheduledAlarms.size)
        // Visit 1 moved by a minute, visit 2 deleted, visit 3 rated.
        val moved = showtime(1, at.plus(min(1)))
        scheduler.schedule(listOf(moved), now)
        assertEquals(listOf(moved), own())
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(mapOf("before:1" to moved.at.toEpochMilli()), scheduler.storedAlarms())
    }

    @Test
    fun anAlarmDueButNotDeliveredYetIsNotCancelled() {
        val scheduler = AlarmReminderScheduler(context)
        scheduler.schedule(listOf(showtime(1)), at.minus(min(9)))
        // Due at 16:15; Android hasn't delivered it yet (the phone is busy) when the app plans
        // again a moment later: the reminder's time has passed, so it isn't planned.
        scheduler.schedule(emptyList(), at.plusSeconds(2))
        assertEquals("left to the check when it fires", listOf(showtime(1)), own())
        assertEquals("forgotten", emptyMap<String, Long>(), scheduler.storedAlarms())
        // A later plan doesn't touch it either.
        scheduler.schedule(listOf(rate(1)), at.plus(min(3)))
        assertEquals(listOf(showtime(1), rate(1)), own())
    }

    @Test
    fun anEmptyPlanCancelsEveryAlarmAhead() {
        val scheduler = AlarmReminderScheduler(context)
        scheduler.schedule(listOf(showtime(1), rate(1)), at.minus(min(9)))
        assertEquals(2, alarms.scheduledAlarms.size)
        scheduler.schedule(emptyList(), at.minus(min(9)))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(emptyMap<String, Long>(), scheduler.storedAlarms())
    }

    @Test
    fun theAlarmsSetOutliveTheProcess() {
        val now = at.minus(min(9))
        AlarmReminderScheduler(context).schedule(listOf(showtime(1), rate(2)), now)
        // The next process (a new scheduler) knows them and cancels the ones of a deleted visit.
        val next = AlarmReminderScheduler(context)
        assertEquals(setOf("before:1", "rate:2"), next.storedAlarms().keys)
        next.schedule(listOf(showtime(1)), now)
        assertEquals(listOf(showtime(1)), own())
    }

    @Test
    fun alarmsThatAreGoneAreSetAgain() {
        val scheduler = AlarmReminderScheduler(context)
        scheduler.schedule(listOf(showtime(1)), at.minus(min(9)))
        // A restart or a force stop drops every alarm; the stored ones stay.
        alarmManager.cancelAll()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        scheduler.schedule(listOf(showtime(1)), at.minus(min(8)))
        assertEquals(listOf(showtime(1)), own())
    }

    @Test
    fun aPostedReminderIsNotSetAgainUntilItsTimeHasPassed() {
        val scheduler = AlarmReminderScheduler(context)
        val reminder = showtime(1)
        scheduler.schedule(listOf(reminder, rate(1)), at.minus(Duration.ofDays(1)))
        // Its alarm came a second early and was posted; the plan right after still has it.
        val early = at.minusSeconds(1)
        alarmManager.cancel(checkNotNull(alarms.scheduledAlarms.first { it.triggerAtMs == at.toEpochMilli() }.operation))
        scheduler.markPosted(reminder)
        scheduler.schedule(listOf(reminder, rate(1)), early)
        assertEquals("not set again", listOf(rate(1)), own())
        assertEquals(setOf("rate:1"), scheduler.storedAlarms().keys)
        // Also not by the next process.
        AlarmReminderScheduler(context).schedule(listOf(reminder, rate(1)), early.plusMillis(500))
        assertEquals(listOf(rate(1)), own())
        assertEquals(mapOf("before:1" to at.toEpochMilli()), scheduler.postedReminders())
        // A posted reminder whose alarm is still there: it is cancelled.
        val other = showtime(2, at.plus(min(2)))
        scheduler.schedule(listOf(other, rate(1)), early)
        scheduler.markPosted(other)
        scheduler.schedule(listOf(other, rate(1)), early)
        assertEquals(listOf(rate(1)), own())

        // The same visit's reminder at another time (the start moved) is a new one.
        val moved = showtime(1, at.plus(min(30)))
        scheduler.schedule(listOf(moved, rate(1)), early)
        assertEquals(listOf(moved, rate(1)), own())

        // Once its time has passed, the mark is forgotten (no plan has the reminder any more).
        scheduler.schedule(listOf(moved, rate(1)), at.plusMillis(1))
        assertEquals(mapOf("before:2" to other.at.toEpochMilli()), scheduler.postedReminders())
    }

    @Test
    fun aRetryIsAlwaysSetAlsoAfterARestart() {
        val scheduler = AlarmReminderScheduler(context)
        val now = at.minus(Duration.ofHours(3))
        scheduler.scheduleRetry(now.plus(min(15)), now)
        assertEquals(now.plus(min(15)), checkIn())
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarms.scheduledAlarms.single().windowLengthMs)

        // The phone restarts: every alarm is gone, the store still lists the check-in. Planning
        // fails again a minute later: the check-in is set again, at the earlier time still ahead.
        alarmManager.cancelAll()
        scheduler.scheduleRetry(now.plus(min(16)), now.plus(min(1)))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(now.plus(min(15)), checkIn())

        // A stored check-in whose time has passed doesn't count.
        alarmManager.cancelAll()
        scheduler.scheduleRetry(now.plus(min(35)), now.plus(min(20)))
        assertEquals(now.plus(min(35)), checkIn())

        // A plan that works cancels it.
        scheduler.schedule(listOf(showtime(1)), now.plus(min(20)))
        assertNull(checkIn())
        assertEquals(listOf(showtime(1)), own())
    }

    @Test
    fun theAlarmIntentCarriesTheReminderAndNothingElseIsAccepted() {
        val reminder = showtime(7)
        assertEquals(reminder, ReminderAlarms.reminderOf(ReminderAlarms.intent(context, reminder)))
        assertEquals(rate(7), ReminderAlarms.reminderOf(ReminderAlarms.intent(context, rate(7))))
        assertNull(ReminderAlarms.reminderOf(ReminderAlarms.intent(context, reminder).setAction("other")))
        assertNull(ReminderAlarms.reminderOf(ReminderAlarms.intent(context, reminder).putExtra("date", "17.10.2026")))
        assertNull(ReminderAlarms.reminderOf(ReminderAlarms.intent(context, reminder).putExtra("leadHours", 5)))
        assertNull(ReminderAlarms.reminderOf(ReminderAlarms.intent(context, reminder).putExtra("visitId", 0L)))
        assertNull(ReminderAlarms.reminderOf(ReminderAlarms.baseIntent(context, 7, ReminderKind.RATE_VISIT)))
        assertNull(ReminderAlarms.reminderOf(ReminderAlarms.checkInIntent(context)))
        assertNull(ReminderAlarms.reminderOf(null))
        assertEquals(7L to ReminderKind.RATE_VISIT, ReminderAlarms.parseKey("rate:7"))
        assertNull(ReminderAlarms.parseKey("later:7"))
        assertNull(ReminderAlarms.parseKey("rate:x"))
        assertNull(ReminderAlarms.parseKey(ReminderAlarms.CHECK_IN_KEY))
    }
}
