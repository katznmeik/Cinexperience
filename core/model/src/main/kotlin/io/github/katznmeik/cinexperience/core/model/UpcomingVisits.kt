package io.github.katznmeik.cinexperience.core.model

import java.time.Duration
import java.time.Instant

/**
 * Upcoming visits (spec §4.4) in the order the app shows them: All visits lists
 * them in their own section (§6.8). The Next visit card (§6.1) has its own,
 * wider rule, [forNextVisitCard], in the same order.
 */
object UpcomingVisits {
    /**
     * Soonest first: by date, then by start time (Berlin clock time, like the date;
     * a visit without one comes after the visits of its day that have one), then by
     * id (the visit saved first), so equal showings keep a fixed order.
     */
    val SOONEST_FIRST: Comparator<Visit> =
        compareBy<Visit> { it.date }.thenBy(nullsLast()) { it.startTime }.thenBy { it.id }

    /** How long after its start a showing stays on the Next visit card (spec §6.1). */
    val NEXT_VISIT_CARD_AFTER_START: Duration = Duration.ofMinutes(30)

    /** The upcoming visits among [items] at [now] ([Visit.isUpcoming]), soonest first. */
    fun of(items: List<VisitListItem>, now: Instant): List<VisitListItem> =
        items.filter { it.visit.isUpcoming(now) }.sortedWith(compareBy(SOONEST_FIRST) { it.visit })

    /**
     * The visits the Next visit card (spec §6.1) can show at [now], soonest first
     * (its first one is the card): a visit whose showing is ahead or started less
     * than [NEXT_VISIT_CARD_AFTER_START] ago, and a visit without a start time from
     * the start of its day (Berlin) until midnight. So the card is wider than
     * [of]: a showing that has just started (the adverts run) and today's visits
     * without a start time stay on it. Everything else uses [of].
     */
    fun forNextVisitCard(items: List<VisitListItem>, now: Instant): List<VisitListItem> =
        items.filter { it.visit.isForNextVisitCard(now) }.sortedWith(compareBy(SOONEST_FIRST) { it.visit })

    private fun Visit.isForNextVisitCard(now: Instant): Boolean {
        val start = startInstant
        return if (start != null) start.plus(NEXT_VISIT_CARD_AFTER_START).isAfter(now) else !date.isBefore(ShowingTime.today(now))
    }
}

/** Whether the visit's showing has started at [now]: it has a start time, and that lies at or before [now]. */
fun Visit.hasStarted(now: Instant): Boolean = startInstant?.let { !it.isAfter(now) } == true
