package io.github.katznmeik.cinexperience.reminders

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.ReminderAlarmChain
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The reminders' alarms (spec §11.3 rules 4 and 5): normal, inexact alarms that fire
 * while idle (`setAndAllowWhileIdle`, `RTC_WAKEUP`), immutable broadcasts to the app's
 * own receiver. A reminder at most 10 minutes away has its own alarm; the others share
 * one check-in, which leads to the next step of the chain ([ReminderAlarmChain]). A new
 * plan replaces and cancels, never an alarm whose time has passed (it may be on its
 * way), and the alarms set outlive the process.
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

    /** The reminders with their own alarm, soonest first. */
    private fun own(): List<PlannedReminder> = alarms.scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == ReminderAlarms.ACTION }
        .sortedBy { it.triggerAtMs }
        .map { checkNotNull(ReminderAlarms.reminderOf(shadowOf(it.operation).savedIntent)) }

    /** The check-in's time, `null` without one. */
    private fun checkIn(): Instant? = alarms.scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == ReminderAlarms.ACTION_CHECK_IN }
        .also { assertTrue("one check-in at most", it.size <= 1) }
        .singleOrNull()?.let { Instant.ofEpochMilli(it.triggerAtMs) }

    @Test
    fun eachAlarmIsAnInexactBroadcastThatFiresWhileIdle() {
        val close = showtime(1)
        AlarmReminderScheduler(context).schedule(listOf(close, rate(2, at.plus(Duration.ofDays(3)))), at.minus(min(9)))

        assertEquals(2, alarms.scheduledAlarms.size)
        for (alarm in alarms.scheduledAlarms) {
            assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
            assertTrue("allowed while idle", alarm.isAllowWhileIdle)
            // setAndAllowWhileIdle: Android's heuristic window, never an exact alarm.
            assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
            val operation = shadowOf(alarm.operation)
            assertTrue(operation.isBroadcast)
            assertTrue("immutable", operation.isImmutable)
            assertEquals(ComponentName(context, ReminderAlarmReceiver::class.java), operation.savedIntent.component)
        }
        // The close one has its own alarm at its time; the other a check-in.
        assertEquals(listOf(close), own())
        assertEquals(close.at.toEpochMilli(), alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == ReminderAlarms.ACTION }.triggerAtMs)
        assertEquals(at.plus(Duration.ofDays(3)).minus(min(65)), checkIn())
    }

    /** Plans [reminder] at [from] and then at every check-in, as each fires on time; the trigger times, the last its own alarm. */
    private fun chain(reminder: PlannedReminder, from: Instant): List<Pair<Instant, Instant>> {
        val scheduler = AlarmReminderScheduler(context)
        val steps = ArrayList<Pair<Instant, Instant>>()
        var now = from
        while (steps.size < 10) {
            scheduler.schedule(listOf(reminder), now)
            val next = checkIn()
            if (next == null) {
                assertEquals(listOf(reminder), own())
                steps += now to reminder.at
                return steps
            }
            assertTrue(own().isEmpty())
            steps += now to next
            // The check-in fires on time: delivered, it is gone.
            alarmManager.cancel(checkNotNull(alarms.scheduledAlarms.single().operation))
            now = next
        }
        error("no end")
    }

    @Test
    fun eachCheckInLeadsToTheNextUntilTheRemindersOwnAlarm() {
        for ((ahead, minutesBefore) in listOf(
            Duration.ofDays(3) to listOf(65L, 30, 16, 9, 0),
            Duration.ofHours(5) to listOf(65L, 30, 16, 9, 0),
            min(70) to listOf(32L, 16, 10, 7, 0),
            min(9) to listOf(0L),
        )) {
            val steps = chain(showtime(1), at.minus(ahead))
            assertEquals("$ahead", minutesBefore, steps.map { (_, trigger) -> Duration.between(trigger, at).toMinutes() })
            // Each check-in comes 5 minutes before the reminder at the latest; the reminder's own
            // alarm is set with at most 10 minutes to go, so it is at most 7.5 minutes late.
            for ((setAt, trigger) in steps.dropLast(1)) {
                assertTrue(!trigger.plus(ReminderAlarmChain.window(trigger, setAt)).isAfter(at.minus(min(5))))
            }
            val (setAt, trigger) = steps.last()
            assertEquals(at, trigger)
            assertTrue("$ahead", ReminderAlarmChain.window(trigger, setAt) <= Duration.ofSeconds(450))
            alarmManager.cancelAll()
        }
    }

    @Test
    fun severalRemindersCloseTogetherShareOneCheckIn() {
        val scheduler = AlarmReminderScheduler(context)
        val first = showtime(1)
        val second = showtime(2, at.plus(min(3)))
        val later = rate(3, at.plus(Duration.ofHours(2)))
        scheduler.schedule(listOf(first, second, later), at.minus(Duration.ofHours(3)))
        // One alarm for all three: the check-in the soonest one needs.
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(at.minus(min(65)), checkIn())

        // 8 minutes before the first: it has its own alarm, the others share the check-in the second one needs.
        val now = at.minus(min(8))
        scheduler.schedule(listOf(first, second, later), now)
        assertEquals(listOf(first), own())
        assertEquals(ReminderAlarmChain.checkInAt(second.at, now), checkIn())
        assertEquals(2, alarms.scheduledAlarms.size)

        // 2 minutes before the first: both have their own alarm, the third still a check-in.
        scheduler.schedule(listOf(first, second, later), at.minus(min(2)))
        assertEquals(listOf(first, second), own())
        assertEquals(ReminderAlarmChain.checkInAt(later.at, at.minus(min(2))), checkIn())
    }

    @Test
    fun aNewPlanReplacesTheAlarmsAndCancelsTheOthersStillAhead() {
        val scheduler = AlarmReminderScheduler(context)
        val now = at.minus(min(9))
        scheduler.schedule(listOf(showtime(1), showtime(2, at.plus(min(1))), rate(3)), now)
        // Visit 1 moved by a minute, visit 2 deleted, visit 3 rated.
        val moved = showtime(1, at.plus(min(1)))
        scheduler.schedule(listOf(moved), now)
        assertEquals(listOf(moved), own())
        assertNull(checkIn())
        assertEquals(mapOf("before:1" to moved.at.toEpochMilli()), scheduler.storedAlarms())
    }

    @Test
    fun anAlarmDueButNotDeliveredYetIsNotCancelled() {
        val scheduler = AlarmReminderScheduler(context)
        scheduler.schedule(listOf(showtime(1)), at.minus(min(9)))
        // Due at 16:15; Android hasn't delivered it yet (an inexact alarm comes late) when the app
        // opens at 16:17 and plans again: the reminder's time has passed, so it isn't planned.
        scheduler.schedule(emptyList(), at.plus(min(2)))
        assertEquals("left to the check when it fires", listOf(showtime(1)), own())
        assertEquals("forgotten", emptyMap<String, Long>(), scheduler.storedAlarms())
        // A later plan doesn't touch it either.
        scheduler.schedule(listOf(rate(1)), at.plus(min(3)))
        assertEquals(listOf(showtime(1)), own())
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
        assertEquals(setOf("before:1", ReminderAlarms.CHECK_IN_KEY), next.storedAlarms().keys)
        next.schedule(listOf(showtime(1)), now)
        assertEquals(listOf(showtime(1)), own())
        assertNull(checkIn())
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
    fun aRetryIsACheckInThatKeepsAnEarlierOne() {
        val scheduler = AlarmReminderScheduler(context)
        val now = at.minus(Duration.ofHours(3))
        scheduler.scheduleRetry(now.plus(min(15)), now)
        assertEquals(now.plus(min(15)), checkIn())
        // A check-in already set for earlier stays.
        scheduler.schedule(listOf(showtime(1, now.plus(min(30)))), now)
        val planned = checkIn()
        scheduler.scheduleRetry(now.plus(min(15)), now)
        assertEquals(planned, checkIn())
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
