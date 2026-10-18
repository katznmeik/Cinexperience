package io.github.katznmeik.cinexperience.core.model

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * When to wake the phone for the reminders (spec §11.3 rule 5), with Android's normal,
 * inexact alarms only. Android may deliver such an alarm set at `now` for `at` as late as
 * [window]: `min(0.75 × (at − now), 1 h)`. A reminder's own alarm is therefore set only
 * once it is close ([FINAL_WITHIN]), so it comes at most [FINAL_WITHIN] × 0.75 late;
 * while it is further away, a check-in alarm wakes the app to plan again. A check-in is
 * set as late as possible such that even its latest delivery leaves [CHECK_IN_ARRIVES_BY]
 * before the reminder, so the next plan is always made in time. Far away (more than
 * about 2 h 25 min), one check-in an hour and 5 minutes before is enough (its window is
 * the full hour); after that, each check-in leaves at most 3/7 of the time beyond
 * [CHECK_IN_ARRIVES_BY], so a few steps reach [FINAL_WITHIN].
 *
 * Pure: "now" is passed in.
 */
object ReminderAlarmChain {
    /** A reminder's own alarm is set once at most this much time is left (window ≤ 7.5 min). */
    val FINAL_WITHIN: Duration = Duration.ofMinutes(10)

    /** A check-in arrives at the latest this long before the reminder. */
    val CHECK_IN_ARRIVES_BY: Duration = Duration.ofMinutes(5)

    /** Android's longest delivery window of a one-shot inexact alarm. */
    val MAX_WINDOW: Duration = Duration.ofHours(1)

    /** A firing alarm counts as the reminder's own from this long before the reminder's time (clock jitter). */
    val DUE_TOLERANCE: Duration = Duration.ofMinutes(1)

    /** How late Android may deliver an inexact alarm set at [now] for [at]: `min(0.75 × (at − now), 1 h)`. */
    fun window(at: Instant, now: Instant): Duration {
        val futurity = Duration.between(now, at).coerceAtLeast(Duration.ZERO)
        return minOf(futurity.multipliedBy(3).dividedBy(4), MAX_WINDOW)
    }

    /** Whether the reminder at [at] gets its own alarm at [now]: at most [FINAL_WITHIN] left. */
    fun isFinal(at: Instant, now: Instant): Boolean = Duration.between(now, at) <= FINAL_WITHIN

    /**
     * The latest check-in for a reminder at [at] that, set at [now], is delivered at the
     * latest [CHECK_IN_ARRIVES_BY] before it: `c` with `c + window(c, now) = at − 5 min`.
     * Only for a reminder that isn't [isFinal] (more than [FINAL_WITHIN] left).
     */
    fun checkInAt(at: Instant, now: Instant): Instant {
        require(!isFinal(at, now)) { "A reminder this close gets its own alarm" }
        val budget = Duration.between(now, at).minus(CHECK_IN_ARRIVES_BY)
        // With the full hour as window (a check-in at least 80 minutes ahead) the check-in is an hour
        // before the latest arrival; closer, the window is 0.75 of the distance: d + 0.75 d = budget.
        val fullWindowFrom = MAX_WINDOW.plus(MAX_WINDOW.multipliedBy(4).dividedBy(3))
        val distance = if (budget >= fullWindowFrom) budget.minus(MAX_WINDOW) else budget.multipliedBy(4).dividedBy(7)
        // To the millisecond, as Android's alarms take it (earlier, so still in time).
        return now.plus(distance).truncatedTo(ChronoUnit.MILLIS)
    }

    /**
     * Whether an alarm firing at [now] is the reminder at [at] itself (its time has come,
     * also when Android delivered it late), not a check-in before it.
     */
    fun isDue(at: Instant, now: Instant): Boolean = !now.isBefore(at.minus(DUE_TOLERANCE))
}
