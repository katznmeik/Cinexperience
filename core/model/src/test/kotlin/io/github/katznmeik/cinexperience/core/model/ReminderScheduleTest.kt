package io.github.katznmeik.cinexperience.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the reminders come (spec §11.3 e and f, rules 2 and 3), in Berlin time. */
class ReminderScheduleTest {

    private val date = LocalDate.of(2026, 10, 17)
    private val evening = LocalTime.of(20, 15) // 18:15 UTC (summer time)
    private fun at(text: String): Instant = Instant.parse(text)
    private val morning = at("2026-10-17T08:00:00Z")

    private fun visit(id: Long = 1, date: LocalDate = this.date, startTime: LocalTime? = evening) =
        Visit(id = id, hallId = 1, date = date, startTime = startTime)

    private val both = ReminderSettings(beforeShowing = true, rateAfterVisit = true)

    private fun plan(
        visits: List<Visit>,
        settings: ReminderSettings = both,
        runtimes: Map<Long, Int> = emptyMap(),
        now: Instant = morning,
    ) = ReminderSchedule.plan(visits, settings, runtimes, now)

    private fun List<PlannedReminder>.times(kind: ReminderKind) = filter { it.kind == kind }.map { it.at }

    @Test
    fun theShowtimeReminderComesTheLeadTimeBeforeTheStart() {
        val expected = mapOf(
            ReminderLeadTime.ONE_HOUR to at("2026-10-17T17:15:00Z"),
            ReminderLeadTime.TWO_HOURS to at("2026-10-17T16:15:00Z"),
            ReminderLeadTime.THREE_HOURS to at("2026-10-17T15:15:00Z"),
        )
        for ((lead, time) in expected) {
            val planned = plan(listOf(visit()), ReminderSettings(beforeShowing = true, leadTime = lead))
            assertEquals(
                listOf(PlannedReminder(1, ReminderKind.BEFORE_SHOWING, time, date, evening, lead)),
                planned,
                "$lead",
            )
        }
        assertEquals(ReminderLeadTime.TWO_HOURS, ReminderSettings().leadTime)
        assertEquals(listOf(1, 2, 3), ReminderLeadTime.entries.map { it.hours })
        assertEquals(ReminderLeadTime.THREE_HOURS, ReminderLeadTime.ofHours(3))
        assertEquals(null, ReminderLeadTime.ofHours(4))
    }

    @Test
    fun rateYourVisitComesHalfAnHourAfterTheEnd() {
        // With the program's runtime: 20:15 + 118 min = 22:13, + 30 min = 22:43 (20:43 UTC).
        assertEquals(listOf(at("2026-10-17T20:43:00Z")), plan(listOf(visit()), runtimes = mapOf(1L to 118)).times(ReminderKind.RATE_VISIT))
        // Without it: start + 2.5 h + 30 min = 23:15 (21:15 UTC).
        assertEquals(listOf(at("2026-10-17T21:15:00Z")), plan(listOf(visit())).times(ReminderKind.RATE_VISIT))
        // A runtime of another visit doesn't count; one outside 1–600 minutes counts as unknown.
        assertEquals(listOf(at("2026-10-17T21:15:00Z")), plan(listOf(visit()), runtimes = mapOf(2L to 90)).times(ReminderKind.RATE_VISIT))
        for (invalid in listOf(0, -5, 601)) {
            assertEquals(listOf(at("2026-10-17T21:15:00Z")), plan(listOf(visit()), runtimes = mapOf(1L to invalid)).times(ReminderKind.RATE_VISIT))
        }
        assertEquals(Duration.ofMinutes(150), ReminderSchedule.RUNTIME_UNKNOWN)
        assertEquals(Duration.ofMinutes(30), ReminderSchedule.RATE_AFTER_END)
    }

    @Test
    fun visitsWithoutAStartTimeGetNoReminders() {
        assertEquals(emptyList(), plan(listOf(visit(startTime = null), visit(2, date.plusDays(3), startTime = null))))
    }

    @Test
    fun nothingIsPlannedWhileBothAreOff() {
        assertEquals(emptyList(), plan(listOf(visit()), ReminderSettings()))
        // Only what is switched on.
        assertEquals(setOf(ReminderKind.BEFORE_SHOWING), plan(listOf(visit()), ReminderSettings(beforeShowing = true)).map { it.kind }.toSet())
        assertEquals(setOf(ReminderKind.RATE_VISIT), plan(listOf(visit()), ReminderSettings(rateAfterVisit = true)).map { it.kind }.toSet())
    }

    @Test
    fun aReminderWhoseTimeHasPassedIsNotPlanned() {
        // 19:00 Berlin (17:00 UTC): the 2 h reminder (18:15) has passed, the showing hasn't started.
        val planned = plan(listOf(visit()), now = at("2026-10-17T17:00:00Z"))
        assertEquals(listOf(ReminderKind.RATE_VISIT), planned.map { it.kind })
        // Exactly at the reminder's time it has passed too.
        assertEquals(emptyList(), plan(listOf(visit()), ReminderSettings(beforeShowing = true), now = at("2026-10-17T16:15:00Z")))
        assertEquals(1, plan(listOf(visit()), ReminderSettings(beforeShowing = true), now = at("2026-10-17T16:14:59Z")).size)
        // During the film (it has started, so it isn't upcoming): Rate your visit stays planned.
        assertEquals(listOf(at("2026-10-17T21:15:00Z")), plan(listOf(visit()), now = at("2026-10-17T19:00:00Z")).times(ReminderKind.RATE_VISIT))
        // After Rate your visit's time, and for yesterday's visit: nothing.
        assertEquals(emptyList(), plan(listOf(visit()), now = at("2026-10-17T21:15:00Z")))
        assertEquals(emptyList(), plan(listOf(visit(date = date.minusDays(1)))))
    }

    @Test
    fun aRatedVisitGetsNoRateReminder() {
        val ratings = listOf(
            visit().copy(seatRating = Rating(4)),
            visit().copy(hallRatings = HallRatings.NONE.withRating(HallCategory.entries.last(), Rating(3))),
            visit().copy(cinemaRatings = CinemaRatings.NONE.withRating(CinemaCategory.entries.first(), Rating(5))),
        )
        for (rated in ratings) {
            assertTrue(rated.hasRating)
            assertEquals(listOf(ReminderKind.BEFORE_SHOWING), plan(listOf(rated)).map { it.kind }, "$rated")
        }
        assertFalse(visit().hasRating)
    }

    @Test
    fun aStartInTheSpringGapIsTakenAsSummerTime() {
        // 29 Mar 2026: 02:30 doesn't exist and becomes 03:30 summer time (01:30 UTC).
        val spring = LocalDate.of(2026, 3, 29)
        val now = at("2026-03-28T12:00:00Z")
        val gap = plan(listOf(visit(date = spring, startTime = LocalTime.of(2, 30))), now = now)
        assertEquals(at("2026-03-28T23:30:00Z"), gap.times(ReminderKind.BEFORE_SHOWING).single()) // 00:30 winter time
        assertEquals(at("2026-03-29T04:30:00Z"), gap.times(ReminderKind.RATE_VISIT).single())
        // 03:30 on that day is the same moment; 2 h before is two real hours (00:30, before the change).
        val afterGap = plan(listOf(visit(date = spring, startTime = LocalTime.of(3, 30))), now = now)
        assertEquals(gap.map { it.at }, afterGap.map { it.at })
        // An evening start that day: 20:15 summer time (18:15 UTC), 1 h before = 17:15 UTC.
        assertEquals(
            listOf(at("2026-03-29T17:15:00Z")),
            plan(listOf(visit(date = spring)), ReminderSettings(beforeShowing = true, leadTime = ReminderLeadTime.ONE_HOUR), now = now)
                .times(ReminderKind.BEFORE_SHOWING),
        )
    }

    @Test
    fun aStartInTheRepeatedAutumnHourIsTheFirstOne() {
        // 25 Oct 2026: 02:30 exists twice; the earlier one (summer time, 00:30 UTC) counts.
        val autumn = LocalDate.of(2026, 10, 25)
        val planned = plan(
            listOf(visit(date = autumn, startTime = LocalTime.of(2, 30))),
            ReminderSettings(beforeShowing = true, leadTime = ReminderLeadTime.ONE_HOUR, rateAfterVisit = true),
            runtimes = mapOf(1L to 90),
            now = at("2026-10-24T12:00:00Z"),
        )
        assertEquals(at("2026-10-24T23:30:00Z"), planned.times(ReminderKind.BEFORE_SHOWING).single()) // 01:30 summer time
        // 00:30 UTC + 90 min + 30 min = 02:30 UTC = 03:30 winter time.
        assertEquals(at("2026-10-25T02:30:00Z"), planned.times(ReminderKind.RATE_VISIT).single())
        // A start in the evening after the change: 20:15 winter time = 19:15 UTC, 3 h before = 16:15 UTC.
        assertEquals(
            listOf(at("2026-10-25T16:15:00Z")),
            plan(listOf(visit(date = autumn)), ReminderSettings(beforeShowing = true, leadTime = ReminderLeadTime.THREE_HOURS), now = at("2026-10-24T12:00:00Z"))
                .times(ReminderKind.BEFORE_SHOWING),
        )
    }

    @Test
    fun visitsFarAheadAreScheduledAndImpossibleDatesAreSkipped() {
        val farAhead = visit(2, LocalDate.of(2099, 12, 31))
        val planned = plan(listOf(farAhead, visit(3, LocalDate.MAX), visit(4, LocalDate.of(300_000_000, 1, 1))))
        assertEquals(setOf(2L), planned.map { it.visitId }.toSet())
        assertEquals(at("2099-12-31T17:15:00Z"), planned.times(ReminderKind.BEFORE_SHOWING).single()) // winter time
    }

    @Test
    fun manyVisitsGiveTheSoonestReminders() {
        val visits = (1L..200L).map { visit(it, date.plusDays(it)) }.shuffled(kotlin.random.Random(7))
        val planned = plan(visits)
        assertEquals(ReminderSchedule.MAX_SCHEDULED, planned.size)
        assertEquals(planned.sortedBy { it.at }, planned)
        // Both reminders of the 25 soonest visits.
        assertEquals((1L..25L).toList(), planned.map { it.visitId }.distinct())
        // Equal times: by visit id.
        val twins = plan(listOf(visit(9), visit(5)), runtimes = mapOf(9L to 60, 5L to 60))
        assertEquals(listOf(5L, 9L, 5L, 9L), twins.map { it.visitId })
    }

    @Test
    fun aFiringReminderIsCheckedAgain() {
        val now = at("2026-10-17T16:20:00Z")
        val settings = both
        val showtime = PlannedReminder(1, ReminderKind.BEFORE_SHOWING, at("2026-10-17T16:15:00Z"), date, evening, ReminderLeadTime.TWO_HOURS)
        val rate = PlannedReminder(1, ReminderKind.RATE_VISIT, at("2026-10-17T21:15:00Z"), date, evening)
        assertTrue(ReminderSchedule.isStillDue(showtime, visit(), settings, now))
        assertTrue(ReminderSchedule.isStillDue(rate, visit(), settings, at("2026-10-17T21:20:00Z")))

        // The visit was deleted, or its date or start time changed.
        assertFalse(ReminderSchedule.isStillDue(showtime, null, settings, now))
        assertFalse(ReminderSchedule.isStillDue(showtime, visit(2), settings, now))
        assertFalse(ReminderSchedule.isStillDue(showtime, visit(startTime = LocalTime.of(20, 30)), settings, now))
        assertFalse(ReminderSchedule.isStillDue(showtime, visit(startTime = null), settings, now))
        assertFalse(ReminderSchedule.isStillDue(rate, visit(date = date.plusDays(1)), settings, now))
        // The setting was switched off, or the lead time changed.
        assertFalse(ReminderSchedule.isStillDue(showtime, visit(), settings.copy(beforeShowing = false), now))
        assertFalse(ReminderSchedule.isStillDue(showtime, visit(), settings.copy(leadTime = ReminderLeadTime.ONE_HOUR), now))
        assertFalse(ReminderSchedule.isStillDue(rate, visit(), settings.copy(rateAfterVisit = false), now))
        assertTrue(ReminderSchedule.isStillDue(rate, visit(), settings.copy(beforeShowing = false, leadTime = ReminderLeadTime.ONE_HOUR), now))
        // Too late: the showing has started; the visit has been rated meanwhile.
        assertFalse(ReminderSchedule.isStillDue(showtime, visit(), settings, at("2026-10-17T18:15:00Z")))
        assertTrue(ReminderSchedule.isStillDue(showtime, visit(), settings, at("2026-10-17T18:14:59Z")))
        assertFalse(ReminderSchedule.isStillDue(rate, visit().copy(seatRating = Rating(2)), settings, now))
    }

    @Test
    fun kindCodesAreStable() {
        assertEquals(listOf("before", "rate"), ReminderKind.entries.map { it.code })
        assertEquals(ReminderKind.RATE_VISIT, ReminderKind.fromCode("rate"))
        assertEquals(null, ReminderKind.fromCode("other"))
        assertEquals(null, ReminderKind.fromCode(null))
    }

    @Test
    fun settingsSwitchOneKindAtATime() {
        val off = ReminderSettings()
        assertFalse(off.anyOn)
        assertEquals(ReminderSettings(beforeShowing = true), off.with(ReminderKind.BEFORE_SHOWING, true))
        assertEquals(ReminderSettings(rateAfterVisit = true), off.with(ReminderKind.RATE_VISIT, true))
        assertTrue(off.with(ReminderKind.RATE_VISIT, true).isOn(ReminderKind.RATE_VISIT))
        assertFalse(off.with(ReminderKind.RATE_VISIT, true).isOn(ReminderKind.BEFORE_SHOWING))
    }
}
