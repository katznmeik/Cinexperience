package io.github.katznmeik.cinexperience.reminders

import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Rescheduling (spec §11.3 rule 4): whenever the visits, the reminder settings or the
 * program change, debounced, one run at a time, never during a backup import. The
 * clock stands at 15 Oct 2026, 10:00 in Berlin; the invented visit ([SampleTickets])
 * is on Saturday 17 Oct at 20:15 (18:15 UTC) at delphi LUX, Saal 4.
 */
class ReminderCoordinatorTest {

    private val visit = SampleTickets.VISIT
    private val repository = FakeCinexRepository(SampleTickets.store())
    private val program = FakeProgramRepository()
    private val settings = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true, rateAfterVisit = true))
    private val importing = MutableStateFlow(false)
    private val scheduler = RecordingReminderScheduler()
    private val clock = Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC)

    private fun coordinator() = ReminderCoordinator(repository, repository, program, settings, importing, scheduler, clock)

    private fun TestScope.started(): ReminderCoordinator = coordinator().also {
        it.start(backgroundScope)
        settle()
    }

    /** Lets a change settle past the debounce and the run happen. */
    private fun TestScope.settle() {
        advanceTimeBy(ReminderCoordinator.DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
    }

    private fun at(text: String): Instant = Instant.parse(text)

    private fun List<PlannedReminder>.times() = map { it.kind to it.at }

    private val showtime = ReminderKind.BEFORE_SHOWING to at("2026-10-17T16:15:00Z")
    private val rate = ReminderKind.RATE_VISIT to at("2026-10-17T21:15:00Z")

    @Test
    fun theFirstRunSchedulesTheUpcomingVisitsReminders() = runTest {
        started()
        assertEquals(1, scheduler.plans.size)
        // The sample's other visits are past and have no start time.
        assertEquals(listOf(showtime, rate), scheduler.last!!.times())
        assertTrue(scheduler.last!!.all { it.visitId == visit })
    }

    @Test
    fun changesSettleIntoOneRun() = runTest {
        started()
        repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(19, 0)))
        advanceTimeBy(500)
        repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(21, 0)))
        settle()
        assertEquals(2, scheduler.plans.size)
        // 21:00 summer time = 19:00 UTC.
        assertEquals(
            listOf(ReminderKind.BEFORE_SHOWING to at("2026-10-17T17:00:00Z"), ReminderKind.RATE_VISIT to at("2026-10-17T22:00:00Z")),
            scheduler.last!!.times(),
        )
    }

    @Test
    fun aDeletedOrRatedVisitLosesItsReminders() = runTest {
        started()
        repository.saveVisit(repository.getVisit(visit)!!.copy(seatRating = Rating(4)))
        settle()
        assertEquals(listOf(showtime), scheduler.last!!.times())
        repository.deleteVisit(visit)
        settle()
        assertEquals(emptyList<PlannedReminder>(), scheduler.last)
    }

    @Test
    fun theSettingsDecide() = runTest {
        started()
        settings.update { it.copy(leadTime = ReminderLeadTime.THREE_HOURS) }
        settle()
        assertEquals(listOf(ReminderKind.BEFORE_SHOWING to at("2026-10-17T15:15:00Z"), rate), scheduler.last!!.times())
        settings.update { it.with(ReminderKind.RATE_VISIT, false) }
        settle()
        assertEquals(listOf(ReminderKind.BEFORE_SHOWING), scheduler.last!!.map { it.kind })
        // Both off: an empty plan, which cancels every alarm.
        settings.update { it.with(ReminderKind.BEFORE_SHOWING, false) }
        settle()
        assertEquals(emptyList<PlannedReminder>(), scheduler.last)
        // While both are off, visit changes don't even start a run.
        val runs = scheduler.plans.size
        repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(19, 0)))
        settle()
        assertEquals(runs, scheduler.plans.size)
    }

    @Test
    fun theProgramsRuntimeMovesRateYourVisit() = runTest {
        started()
        program.state.update {
            it.copy(
                program = buildProgram {
                    film(SampleTickets.FILM, runtimeMinutes = 104) { showing("2026-10-17T20:15", "delphi LUX") }
                },
            )
        }
        settle()
        // 18:15 UTC + 104 min + 30 min.
        assertEquals(listOf(showtime, ReminderKind.RATE_VISIT to at("2026-10-17T20:29:00Z")), scheduler.last!!.times())
    }

    @Test
    fun nothingIsScheduledWhileABackupImportRuns() = runTest {
        started()
        importing.value = true
        repository.deleteVisit(visit)
        settle()
        assertEquals(1, scheduler.plans.size)
        assertFalse(coordinator().rescheduleNow())
        assertEquals(1, scheduler.plans.size)

        importing.value = false
        settle()
        assertEquals(2, scheduler.plans.size)
        assertEquals(emptyList<PlannedReminder>(), scheduler.last)
    }

    @Test
    fun rescheduleNowSchedulesFromTheCurrentData() = runTest {
        assertTrue(coordinator().rescheduleNow())
        assertEquals(listOf(showtime, rate), scheduler.last!!.times())
        settings.current.value = ReminderSettings()
        assertTrue(coordinator().rescheduleNow())
        assertEquals(emptyList<PlannedReminder>(), scheduler.last)
    }

    @Test
    fun aFailingRepositoryIsTriedAgain() = runTest {
        repository.observeFailure = IOException("database")
        assertFalse(coordinator().rescheduleNow())
        coordinator().start(backgroundScope)
        settle()
        assertTrue(scheduler.plans.isEmpty())
        repository.observeFailure = null
        advanceTimeBy(6_000)
        settle()
        assertEquals(listOf(showtime, rate), scheduler.last!!.times())
    }
}
