package io.github.katznmeik.cinexperience.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Times of a visit's showing (spec §4.4, §4.7, §11.3). A visit's date and start
 * time are Berlin wall-clock values, as printed on the ticket. Where a moment is
 * needed, they are placed in [ZONE] by the java.time rule (the same as the
 * program's in core:program):
 *
 * - a time in the spring gap that doesn't exist (29 Mar 2026, 02:00–02:59) is
 *   moved forward by the gap's length (02:30 → 03:30 summer time);
 * - a time in the autumn overlap that exists twice (25 Oct 2026, 02:00–02:59) is
 *   taken as the earlier one, still summer time (02:30 = 00:30 UTC).
 */
object ShowingTime {
    /** Yorck's time zone: every date and start time of a visit is Berlin time. */
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /** A ticket's QR modules are removed this many days after the showing (spec §4.7). */
    const val QR_MODULES_KEPT_DAYS: Long = 7

    /** Today's date in Berlin at [now]. */
    fun today(now: Instant): LocalDate = LocalDate.ofInstant(now, ZONE)

    /** The moment a showing on [date] at [startTime] (Berlin clock time) starts, by the rule above. */
    fun startOf(date: LocalDate, startTime: LocalTime): Instant = date.atTime(startTime).atZone(ZONE).toInstant()

    /**
     * Whether a showing on [date] at [startTime] lies after [now] (an upcoming
     * visit, spec §4.4): its start is later than [now]; without a start time, its
     * date is after today in Berlin (a visit later today without a start time is
     * not upcoming).
     */
    fun isUpcoming(date: LocalDate, startTime: LocalTime?, now: Instant): Boolean =
        if (startTime != null) startOf(date, startTime).isAfter(now) else date.isAfter(today(now))

    /**
     * When a ticket's QR modules expire (spec §4.7): [QR_MODULES_KEPT_DAYS] days
     * after the showing's start, counted in Berlin days (the same clock time a
     * week later); without a start time, that many days after the end of [date]
     * (midnight at its end, Berlin time).
     */
    fun qrModulesExpireAt(date: LocalDate, startTime: LocalTime?): Instant {
        val from = if (startTime != null) date.atTime(startTime).atZone(ZONE) else date.plusDays(1).atStartOfDay(ZONE)
        return from.plusDays(QR_MODULES_KEPT_DAYS).toInstant()
    }

    /** Whether the QR modules of a ticket for a showing on [date] at [startTime] have expired at [now]. */
    fun qrModulesExpired(date: LocalDate, startTime: LocalTime?, now: Instant): Boolean =
        !now.isBefore(qrModulesExpireAt(date, startTime))

    /** Whether [time] is to the minute, as a visit's start time must be ("HH:mm"). */
    fun isWholeMinute(time: LocalTime): Boolean = time.second == 0 && time.nano == 0
}

/** The moment the visit's showing starts ([ShowingTime.startOf]); `null` without a start time. */
val Visit.startInstant: Instant? get() = startTime?.let { ShowingTime.startOf(date, it) }

/** Whether the visit is an upcoming visit at [now] (spec §4.4, see [ShowingTime.isUpcoming]). */
fun Visit.isUpcoming(now: Instant): Boolean = ShowingTime.isUpcoming(date, startTime, now)

/** When the QR modules of this visit's ticket expire ([ShowingTime.qrModulesExpireAt]). */
val Visit.qrModulesExpireAt: Instant get() = ShowingTime.qrModulesExpireAt(date, startTime)
