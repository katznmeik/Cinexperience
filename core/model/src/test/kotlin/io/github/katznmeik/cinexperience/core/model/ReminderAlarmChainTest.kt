package io.github.katznmeik.cinexperience.core.model

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The re-arm chain (spec §11.3 rule 5, inexact alarms only): check-ins while the
 * reminder is far away, its own alarm once at most 10 minutes are left; even when every
 * alarm comes as late as Android allows, the reminder is at most 7.5 minutes late.
 */
class ReminderAlarmChainTest {

    private val reminder = Instant.parse("2026-10-17T16:15:00Z")
    private fun min(minutes: Long): Duration = Duration.ofMinutes(minutes)

    /** One step of the chain: an alarm at [at], set at [setAt]; [final] = the reminder's own. */
    private data class Step(val setAt: Instant, val at: Instant, val final: Boolean)

    /**
     * The chain from [start] on; each check-in fires at its time ([late] false) or as late as
     * Android may deliver it ([late] true), and the next step is planned then.
     */
    private fun chain(start: Instant, late: Boolean = false): List<Step> {
        val steps = ArrayList<Step>()
        var now = start
        while (steps.size < 20) {
            if (ReminderAlarmChain.isFinal(reminder, now)) {
                steps += Step(now, reminder, final = true)
                return steps
            }
            val checkIn = ReminderAlarmChain.checkInAt(reminder, now)
            steps += Step(now, checkIn, final = false)
            now = if (late) checkIn.plus(ReminderAlarmChain.window(checkIn, now)) else checkIn
        }
        error("no end")
    }

    @Test
    fun androidsWindowIsThreeQuartersOfTheDistanceAtMostAnHour() {
        val now = reminder.minus(min(40))
        assertEquals(min(30), ReminderAlarmChain.window(reminder, now))
        assertEquals(min(60), ReminderAlarmChain.window(reminder, reminder.minus(min(300))))
        assertEquals(Duration.ZERO, ReminderAlarmChain.window(reminder, reminder.plus(min(5))))
    }

    @Test
    fun withNineMinutesLeftTheReminderGetsItsOwnAlarm() {
        assertEquals(listOf(Step(reminder.minus(min(9)), reminder, final = true)), chain(reminder.minus(min(9))))
        assertTrue(ReminderAlarmChain.isFinal(reminder, reminder.minus(min(10))))
        assertFalse(ReminderAlarmChain.isFinal(reminder, reminder.minus(min(10)).minusSeconds(1)))
        assertFailsWith<IllegalArgumentException> { ReminderAlarmChain.checkInAt(reminder, reminder.minus(min(9))) }
        // Its window is at most 7.5 minutes.
        assertEquals(Duration.ofSeconds(450), ReminderAlarmChain.window(reminder, reminder.minus(min(10))))
    }

    @Test
    fun farAwayOneCheckInAnHourAndFiveMinutesBefore() {
        for (ahead in listOf(Duration.ofDays(3), Duration.ofHours(5), min(146))) {
            val steps = chain(reminder.minus(ahead))
            assertEquals(reminder.minus(min(65)), steps.first().at, "$ahead")
            // Then three check-ins at 30.7, 16 and 9.7 minutes before, and the reminder's own alarm.
            assertEquals(5, steps.size, "$ahead")
            assertEquals(listOf(30L, 16L, 9L), steps.drop(1).dropLast(1).map { Duration.between(it.at, reminder).toMinutes() })
            assertTrue(steps.last().final)
        }
    }

    @Test
    fun seventyMinutesAheadTheStepsShrink() {
        val steps = chain(reminder.minus(min(70)))
        // 65 min to the latest arrival, of which 4/7 to the check-in: 37 min 8.57 s from now.
        assertEquals(reminder.minus(min(70)).plus(Duration.ofMillis(65 * 60_000L * 4 / 7)), steps.first().at)
        assertEquals(listOf(32L, 16L, 10L, 7L), steps.dropLast(1).map { Duration.between(it.at, reminder).toMinutes() })
        assertEquals(Step(steps[3].at, reminder, final = true), steps.last())
    }

    @Test
    fun evenWhenEveryAlarmComesAsLateAsAllowedTheReminderIsAtMostSevenAndAHalfMinutesLate() {
        var ahead = min(11)
        while (ahead <= Duration.ofDays(4)) {
            for (late in listOf(false, true)) {
                val steps = chain(reminder.minus(ahead), late)
                assertTrue(steps.size <= 6, "$ahead late=$late: ${steps.size} steps")
                for (step in steps.dropLast(1)) {
                    assertTrue(step.at.isAfter(step.setAt))
                    // A check-in is delivered 5 minutes before the reminder at the latest.
                    val latest = step.at.plus(ReminderAlarmChain.window(step.at, step.setAt))
                    assertFalse(latest.isAfter(reminder.minus(ReminderAlarmChain.CHECK_IN_ARRIVES_BY)), "$ahead late=$late")
                }
                val final = steps.last()
                assertTrue(final.final)
                assertTrue(ReminderAlarmChain.window(reminder, final.setAt) <= Duration.ofSeconds(450))
            }
            ahead = ahead.plus(if (ahead < Duration.ofHours(3)) Duration.ofSeconds(37) else min(53))
        }
    }

    @Test
    fun anAlarmIsTheRemindersOwnOnceItsTimeHasCome() {
        assertTrue(ReminderAlarmChain.isDue(reminder, reminder))
        assertTrue(ReminderAlarmChain.isDue(reminder, reminder.plus(min(40))))
        assertTrue(ReminderAlarmChain.isDue(reminder, reminder.minusSeconds(60)))
        assertFalse(ReminderAlarmChain.isDue(reminder, reminder.minus(min(5))))
    }
}
