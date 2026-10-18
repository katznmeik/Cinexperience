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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The reminders' alarms (spec §11.3 rules 4 and 5): normal, inexact alarms that fire
 * while idle (`setAndAllowWhileIdle`, `RTC_WAKEUP`), one immutable broadcast per visit
 * and kind to the app's own receiver; a new plan replaces and cancels, and the keys of
 * the alarms set outlive the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlarmReminderSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val alarms: ShadowAlarmManager = shadowOf(alarmManager)

    private val date = LocalDate.of(2026, 10, 17)
    private val start = LocalTime.of(20, 15)

    private fun showtime(visitId: Long, at: String = "2026-10-17T16:15:00Z") =
        PlannedReminder(visitId, ReminderKind.BEFORE_SHOWING, Instant.parse(at), date, start, ReminderLeadTime.TWO_HOURS)

    private fun rate(visitId: Long, at: String = "2026-10-17T21:15:00Z") =
        PlannedReminder(visitId, ReminderKind.RATE_VISIT, Instant.parse(at), date, start)

    /** The reminders the scheduled alarms carry, soonest first. */
    private fun scheduled(): List<PlannedReminder> =
        alarms.scheduledAlarms.sortedBy { it.triggerAtMs }.map { checkNotNull(ReminderAlarms.reminderOf(shadowOf(it.operation).savedIntent)) }

    @Test
    fun eachReminderIsAnInexactAlarmThatFiresWhileIdle() {
        val planned = listOf(showtime(1), rate(1), showtime(2, "2026-10-18T10:00:00Z"))
        AlarmReminderScheduler(context).schedule(planned)

        assertEquals(3, alarms.scheduledAlarms.size)
        for (alarm in alarms.scheduledAlarms) {
            assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
            assertTrue("allowed while idle", alarm.isAllowWhileIdle)
            // setAndAllowWhileIdle: Android's heuristic window, never an exact alarm.
            assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
            val operation = shadowOf(alarm.operation)
            assertTrue(operation.isBroadcast)
            assertTrue("immutable", operation.isImmutable)
            assertEquals(ComponentName(context, ReminderAlarmReceiver::class.java), operation.savedIntent.component)
            assertEquals(ReminderAlarms.ACTION, operation.savedIntent.action)
        }
        assertEquals(planned.sortedBy { it.at }, scheduled())
        assertEquals(planned.map { it.at.toEpochMilli() }.sorted(), alarms.scheduledAlarms.map { it.triggerAtMs }.sorted())
    }

    @Test
    fun aNewPlanReplacesTheAlarmsAndCancelsTheOthers() {
        val scheduler = AlarmReminderScheduler(context)
        scheduler.schedule(listOf(showtime(1), rate(1), showtime(2)))
        // Visit 1 moved to 20:45, visit 2 deleted.
        val moved = listOf(showtime(1, "2026-10-17T16:45:00Z"), rate(1, "2026-10-17T21:45:00Z"))
        scheduler.schedule(moved)
        assertEquals(moved, scheduled())
        assertEquals(setOf("before:1", "rate:1"), scheduler.storedKeys())
    }

    @Test
    fun anEmptyPlanCancelsEveryAlarm() {
        val scheduler = AlarmReminderScheduler(context)
        scheduler.schedule(listOf(showtime(1), rate(1)))
        scheduler.schedule(emptyList())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(emptySet<String>(), scheduler.storedKeys())
    }

    @Test
    fun theKeysOutliveTheProcess() {
        AlarmReminderScheduler(context).schedule(listOf(showtime(1), rate(2)))
        // The next process (a new scheduler) knows them and cancels the alarm of a deleted visit.
        val next = AlarmReminderScheduler(context)
        assertEquals(setOf("before:1", "rate:2"), next.storedKeys())
        next.schedule(listOf(showtime(1)))
        assertEquals(listOf(showtime(1)), scheduled())
    }

    @Test
    fun alarmsThatAreGoneAreSetAgain() {
        val scheduler = AlarmReminderScheduler(context)
        scheduler.schedule(listOf(showtime(1)))
        // A restart or a force stop drops every alarm; the keys stay.
        alarmManager.cancelAll()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        scheduler.schedule(listOf(showtime(1)))
        assertEquals(listOf(showtime(1)), scheduled())
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
        assertNull(ReminderAlarms.reminderOf(null))
        assertEquals(7L to ReminderKind.RATE_VISIT, ReminderAlarms.parseKey("rate:7"))
        assertNull(ReminderAlarms.parseKey("later:7"))
        assertNull(ReminderAlarms.parseKey("rate:x"))
    }
}
