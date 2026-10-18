package io.github.katznmeik.cinexperience.core.model

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The two reminders of v1.4 (spec §11.3 e and f). [code] is stored with a scheduled
 * alarm and must never change for an existing entry.
 */
enum class ReminderKind(val code: String) {
    /** e, the showtime reminder: "Hope starts at 17:15 · …", the lead time before the start. */
    BEFORE_SHOWING("before"),

    /** f, "How was Hope? Rate your visit": about 30 minutes after the film has ended. */
    RATE_VISIT("rate"),
    ;

    companion object {
        fun fromCode(code: String?): ReminderKind? = entries.firstOrNull { it.code == code }
    }
}

/** How long before the start the showtime reminder comes (spec §6.7): 1, 2 or 3 hours, 2 by default. */
enum class ReminderLeadTime(val hours: Int) {
    ONE_HOUR(1),
    TWO_HOURS(2),
    THREE_HOURS(3),
    ;

    val duration: Duration get() = Duration.ofHours(hours.toLong())

    companion object {
        val DEFAULT: ReminderLeadTime = TWO_HOURS

        fun ofHours(hours: Int): ReminderLeadTime? = entries.firstOrNull { it.hours == hours }
    }
}

/**
 * The reminder settings (spec §6.7, §11.3 rule 1): both off until switched on. They
 * are app settings (spec §8): Auto Backup, not the backup file.
 *
 * @property beforeShowing "Before the showing" (e), [leadTime] before the start.
 * @property rateAfterVisit "Rate after the visit" (f).
 */
data class ReminderSettings(
    val beforeShowing: Boolean = false,
    val leadTime: ReminderLeadTime = ReminderLeadTime.DEFAULT,
    val rateAfterVisit: Boolean = false,
) {
    fun isOn(kind: ReminderKind): Boolean = when (kind) {
        ReminderKind.BEFORE_SHOWING -> beforeShowing
        ReminderKind.RATE_VISIT -> rateAfterVisit
    }

    /** These settings with [kind] switched [on]. */
    fun with(kind: ReminderKind, on: Boolean): ReminderSettings = when (kind) {
        ReminderKind.BEFORE_SHOWING -> copy(beforeShowing = on)
        ReminderKind.RATE_VISIT -> copy(rateAfterVisit = on)
    }

    val anyOn: Boolean get() = beforeShowing || rateAfterVisit
}

/**
 * A reminder of visit [visitId]: [kind] at [at], computed from the showing on [date]
 * at [startTime] (Berlin clock time, like the visit's) and, for
 * [ReminderKind.BEFORE_SHOWING], [leadTime]. The alarm carries all of it, so that the
 * reminder can be checked again when it fires ([ReminderSchedule.isStillDue]).
 */
data class PlannedReminder(
    val visitId: Long,
    val kind: ReminderKind,
    val at: Instant,
    val date: LocalDate,
    val startTime: LocalTime,
    val leadTime: ReminderLeadTime? = null,
)

/** Whether the visit has any rating (spec §11.3 rule 3): the seat's, or one of the hall's or cinema's categories. */
val Visit.hasRating: Boolean
    get() = seatRating != null ||
        HallCategory.entries.any { hallRatings[it] != null } ||
        CinemaCategory.entries.any { cinemaRatings[it] != null }

/**
 * When the reminders come (spec §11.3 e and f, rules 2 and 3). Pure: everything,
 * "now" included, is passed in. Times come from the visit's date and start time in
 * Berlin time ([ShowingTime.startOf], also on the days the clocks change) and are
 * counted on the real timeline, so "2 hours before" is two real hours, also across a
 * clock change.
 *
 * - **Before the showing** (e): for each upcoming visit (§4.4) with a start time,
 *   the start minus the lead time.
 * - **Rate your visit** (f): [RATE_AFTER_END] after the film's end, which is the
 *   start plus the runtime when the program has the showing, otherwise plus
 *   [RUNTIME_UNKNOWN]; for each visit with a start time that has no rating
 *   ([hasRating]). A visit stays upcoming until it starts, so its f reminder is
 *   kept by a new plan during the film too.
 * - A reminder whose time has passed is not planned (rule 3), so nothing arrives
 *   late, e.g. after a restart. A visit whose times can't be computed (a date far
 *   outside any real calendar, from damaged data) gets none.
 * - At most [MAX_SCHEDULED], the soonest; the app plans again whenever one fires,
 *   so the later ones follow.
 */
object ReminderSchedule {
    /** "About 30 minutes after the film has ended" (spec §11.3 f). */
    val RATE_AFTER_END: Duration = Duration.ofMinutes(30)

    /** The film's length when the program doesn't have the showing (spec §11.3 rule 2). */
    val RUNTIME_UNKNOWN: Duration = Duration.ofMinutes(150)

    /** Runtimes outside this range (minutes) count as unknown, like the program's (core:program `ProgramRules`). */
    val RUNTIME_MINUTES: IntRange = 1..600

    /** The most reminders scheduled at once, far below Android's limit of 500 alarms per app. */
    const val MAX_SCHEDULED: Int = 50

    /** When the showtime reminder of a showing on [date] at [startTime] comes: [leadTime] before the start. */
    fun beforeShowingAt(date: LocalDate, startTime: LocalTime, leadTime: ReminderLeadTime): Instant =
        ShowingTime.startOf(date, startTime).minus(leadTime.duration)

    /**
     * When "Rate your visit" comes for a showing on [date] at [startTime]: [RATE_AFTER_END]
     * after the start plus [runtimeMinutes] (the program's runtime of the showing's
     * film), or plus [RUNTIME_UNKNOWN] without a valid one.
     */
    fun rateVisitAt(date: LocalDate, startTime: LocalTime, runtimeMinutes: Int?): Instant {
        val runtime = runtimeMinutes?.takeIf { it in RUNTIME_MINUTES }?.let { Duration.ofMinutes(it.toLong()) } ?: RUNTIME_UNKNOWN
        return ShowingTime.startOf(date, startTime).plus(runtime).plus(RATE_AFTER_END)
    }

    /**
     * The reminders to schedule at [now] for [visits] with [settings]: soonest first
     * (by time, visit id, kind), at most [MAX_SCHEDULED]. [runtimes] are the runtimes
     * in minutes of the visits' showings in the program, by visit id (a visit without
     * one gets [RUNTIME_UNKNOWN]).
     */
    fun plan(visits: List<Visit>, settings: ReminderSettings, runtimes: Map<Long, Int>, now: Instant): List<PlannedReminder> {
        if (!settings.anyOn) return emptyList()
        val planned = ArrayList<PlannedReminder>()
        for (visit in visits) {
            val startTime = visit.startTime ?: continue
            try {
                val start = ShowingTime.startOf(visit.date, startTime)
                if (settings.beforeShowing && start.isAfter(now)) {
                    val at = beforeShowingAt(visit.date, startTime, settings.leadTime)
                    if (at.isAfter(now) && isRepresentable(at)) {
                        planned += PlannedReminder(visit.id, ReminderKind.BEFORE_SHOWING, at, visit.date, startTime, settings.leadTime)
                    }
                }
                if (settings.rateAfterVisit && !visit.hasRating) {
                    val at = rateVisitAt(visit.date, startTime, runtimes[visit.id])
                    if (at.isAfter(now) && isRepresentable(at)) {
                        planned += PlannedReminder(visit.id, ReminderKind.RATE_VISIT, at, visit.date, startTime)
                    }
                }
            } catch (e: DateTimeException) {
                // A date outside the range java.time can place on the timeline: no reminders.
            } catch (e: ArithmeticException) {
                // The same, beyond the range of epoch milliseconds.
            }
        }
        return planned.sortedWith(SOONEST_FIRST).take(MAX_SCHEDULED)
    }

    /**
     * Whether [reminder], firing at [now], is still to be shown (spec §11.3): its
     * setting is still on (for e with the same lead time), its visit [visit] (`null`
     * if it was deleted) still has the same date and start time, and it isn't too
     * late: for e the showing hasn't started, for f the visit still has no rating.
     * Whether Android allows notifications is the app's check.
     */
    fun isStillDue(reminder: PlannedReminder, visit: Visit?, settings: ReminderSettings, now: Instant): Boolean {
        if (visit == null || visit.id != reminder.visitId) return false
        if (visit.date != reminder.date || visit.startTime != reminder.startTime) return false
        if (!settings.isOn(reminder.kind)) return false
        return when (reminder.kind) {
            ReminderKind.BEFORE_SHOWING ->
                reminder.leadTime == settings.leadTime && ShowingTime.startOf(reminder.date, reminder.startTime).isAfter(now)
            ReminderKind.RATE_VISIT -> !visit.hasRating
        }
    }

    private val SOONEST_FIRST: Comparator<PlannedReminder> =
        compareBy<PlannedReminder>({ it.at }, { it.visitId }, { it.kind })

    /** Whether Android's alarms (epoch milliseconds) can hold [at]. */
    private fun isRepresentable(at: Instant): Boolean = try {
        at.toEpochMilli()
        true
    } catch (e: ArithmeticException) {
        false
    }
}
