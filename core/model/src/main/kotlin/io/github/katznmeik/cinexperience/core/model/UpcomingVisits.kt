package io.github.katznmeik.cinexperience.core.model

import java.time.Instant

/**
 * Upcoming visits (spec §4.4) in the order the app shows them: the Next visit card
 * on the Cinemas tab takes the first (§6.1), All visits lists them in their own
 * section (§6.8).
 */
object UpcomingVisits {
    /**
     * Soonest first: by date, then by start time (Berlin clock time, like the date;
     * a visit without one comes after the visits of its day that have one), then by
     * id (the visit saved first), so equal showings keep a fixed order.
     */
    val SOONEST_FIRST: Comparator<Visit> =
        compareBy<Visit> { it.date }.thenBy(nullsLast()) { it.startTime }.thenBy { it.id }

    /** The upcoming visits among [items] at [now] ([Visit.isUpcoming]), soonest first. */
    fun of(items: List<VisitListItem>, now: Instant): List<VisitListItem> =
        items.filter { it.visit.isUpcoming(now) }.sortedWith(compareBy(SOONEST_FIRST) { it.visit })
}
