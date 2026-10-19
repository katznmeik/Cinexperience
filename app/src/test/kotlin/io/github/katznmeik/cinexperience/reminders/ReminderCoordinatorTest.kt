package io.github.katznmeik.cinexperience.reminders

import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
    fun aChangeIsPlannedAtOnce() = runTest {
        started()
        // A save, and the app is swiped away right after: the run doesn't wait for the debounce.
        repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(19, 0)))
        runCurrent()
        assertEquals(2, scheduler.plans.size)
        assertEquals(ReminderKind.BEFORE_SHOWING to at("2026-10-17T15:00:00Z"), scheduler.last!!.times().first())
    }

    @Test
    fun quickFurtherChangesSettleIntoOneMoreRun() = runTest {
        started()
        repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(19, 0)))
        runCurrent()
        advanceTimeBy(300)
        repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(20, 0)))
        advanceTimeBy(300)
        repository.saveVisit(repository.getVisit(visit)!!.copy(startTime = LocalTime.of(21, 0)))
        runCurrent()
        assertEquals("the first at once, the others wait", 2, scheduler.plans.size)
        settle()
        assertEquals(3, scheduler.plans.size)
        // 21:00 summer time = 19:00 UTC.
        assertEquals(
            listOf(ReminderKind.BEFORE_SHOWING to at("2026-10-17T17:00:00Z"), ReminderKind.RATE_VISIT to at("2026-10-17T22:00:00Z")),
            scheduler.last!!.times(),
        )
        // Quiet again: the next change is planned at once.
        advanceTimeBy(5_000)
        repository.deleteVisit(visit)
        runCurrent()
        assertEquals(4, scheduler.plans.size)
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
    fun aLastingRepositoryFailureIsTriedAgainWithoutLimitAndNeverCrashes() = runTest {
        repository.observeFailure = IOException("database")
        assertFalse(coordinator().rescheduleNow())
        // Started in the test's background scope: an exception escaping it would fail the test.
        coordinator().start(backgroundScope)
        // Tries after 5, 10, 20, 40, 80 and 160 s, then every 5 minutes: well past three tries.
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertTrue(scheduler.plans.isEmpty())
        // Also during a backup import.
        importing.value = true
        advanceTimeBy(10 * 60_000L)
        importing.value = false
        runCurrent()
        assertTrue(scheduler.plans.isEmpty())

        repository.observeFailure = null
        advanceTimeBy(ReminderCoordinator.MAX_RETRY_DELAY.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(listOf(showtime, rate), scheduler.last!!.times())
    }

    @Test
    fun onceDataArrivesALaterFailureIsTriedAgainAfterFiveSeconds() = runTest {
        // The visits' flow can break while it runs (e.g. the database closes under it).
        val broken = MutableStateFlow(false)
        val tries = ArrayList<Long>()
        val visits = object : VisitRepository by repository {
            override fun observeAllVisits(): Flow<List<VisitListItem>> = flow {
                tries += testScheduler.currentTime
                emitAll(repository.observeAllVisits().combine(broken) { items, isBroken -> if (isBroken) throw IOException("database") else items })
            }
        }
        repository.observeFailure = IOException("database")
        ReminderCoordinator(visits, repository, program, settings, importing, scheduler, clock).start(backgroundScope)
        // Four failures at the start: tried after 5, 10, 20 and 40 s.
        advanceTimeBy(36_000)
        repository.observeFailure = null
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(listOf(0L, 5_000, 15_000, 35_000, 75_000), tries)
        assertEquals(1, scheduler.plans.size)

        // Data arrived: the next failure (at 76 s) starts the pauses at 5 s again (not 80 s).
        broken.value = true
        runCurrent()
        broken.value = false
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(0L, 5_000, 15_000, 35_000, 75_000, 81_000), tries)
        assertEquals(2, scheduler.plans.size)
        assertEquals(listOf(showtime, rate), scheduler.last!!.times())
    }

    @Test
    fun theRetryDelayGrowsToFiveMinutes() {
        assertEquals(listOf(5L, 10, 20, 40, 80, 160, 300, 300), (0L..7L).map { ReminderCoordinator.retryDelay(it).inWholeSeconds })
        assertEquals(300L, ReminderCoordinator.retryDelay(Long.MAX_VALUE).inWholeSeconds)
    }

    @Test
    fun anErrorWhileSchedulingIsCaught() = runTest {
        val failing = object : ReminderScheduler {
            var calls = 0
            override fun schedule(reminders: List<PlannedReminder>, now: Instant) {
                if (calls++ == 0) throw OutOfMemoryError("test")
                scheduler.schedule(reminders, now)
            }

            override fun scheduleRetry(now: Instant) = scheduler.scheduleRetry(now)

            override fun markPosted(reminder: PlannedReminder) = scheduler.markPosted(reminder)

            override fun isPosted(reminder: PlannedReminder): Boolean = scheduler.isPosted(reminder)
        }
        val coordinator = ReminderCoordinator(repository, repository, program, settings, importing, failing, clock)
        assertFalse(coordinator.rescheduleNow())
        coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(showtime, rate), scheduler.last!!.times())
    }

    @Test
    fun aFailedPlanAsksTheSchedulerForARetry() {
        // The scheduler picks the pause (AlarmReminderSchedulerTest).
        coordinator().scheduleRetry()
        assertEquals(listOf(SampleProgram.FETCHED_AT), scheduler.retries)
    }
}
