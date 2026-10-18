package io.github.katznmeik.cinexperience.reminders

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.data.settings.ReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.ReminderSchedule
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Schedules the reminders again whenever what they depend on changes (spec §11.3
 * rule 4): the visits (saved, changed, deleted, replaced by a backup import), the
 * reminder settings, and the program on the phone (the films' runtimes); and once on
 * request ([rescheduleNow]: after a restart, an app update, a time zone change and
 * whenever a reminder fires).
 *
 * Each run plans with [ReminderSchedule.plan] at [clock]'s now and hands the plan to
 * [scheduler]; runs happen one at a time. Nothing is scheduled while a backup import
 * runs ([isImporting]): the run waits until it has ended. While both reminders are
 * off, the visits and the program aren't observed at all (the plan is empty).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ReminderCoordinator(
    private val visitRepository: VisitRepository,
    private val cinemaRepository: CinemaRepository,
    private val programRepository: ProgramRepository,
    private val settingsStore: ReminderSettingsStore,
    private val isImporting: StateFlow<Boolean>,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
    private val debounce: Duration = DEBOUNCE,
) {
    /** What a plan is made from. */
    private data class Inputs(
        val settings: ReminderSettings,
        val visits: List<VisitListItem> = emptyList(),
        val seedKeys: Map<Long, String> = emptyMap(),
        val program: Program? = null,
    )

    private val runs = Mutex()

    /** The app's cinemas' seed keys by cinema id, for the program's showings. */
    private val seedKeys: Flow<Map<Long, String>> = cinemaRepository.observeCinemas()
        .map { cinemas -> cinemas.mapNotNull { summary -> summary.cinema.seedKey?.let { summary.cinema.id to it } }.toMap() }
        .distinctUntilChanged()

    /** The program on the phone, once it has been read at startup. */
    private val program: Flow<Program?> = programRepository.state
        .filter { it.isLoaded }
        .map { it.program }
        .distinctUntilChanged()

    private val inputs: Flow<Inputs> = settingsStore.settings.distinctUntilChanged().flatMapLatest { settings ->
        if (!settings.anyOn) {
            flowOf(Inputs(settings))
        } else {
            combine(visitRepository.observeAllVisits(), seedKeys, program) { visits, keys, program ->
                Inputs(settings, visits, keys, program)
            }
        }
    }

    /**
     * Observes the inputs in [scope] until it ends (started once per process, from
     * `AppContainer.onAppStart`): after each change has settled for [debounce] (and once
     * no backup import runs), one run. A failing repository is tried again a few times.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(inputs, isImporting) { inputs, importing -> inputs.takeUnless { importing } }
            .debounce(debounce)
            .retry(RETRIES) { e ->
                if (e is CancellationException) return@retry false
                delay(RETRY_DELAY)
                true
            }
            .collect { inputs -> if (inputs != null) runSafely { apply(inputs) } }
    }

    /**
     * Schedules from the current data once; false if that wasn't possible (a backup
     * import runs: the observer started by [start] schedules once it has ended, or the
     * data couldn't be read). Never throws, except cancellation of the caller.
     */
    suspend fun rescheduleNow(): Boolean {
        if (isImporting.value) return false
        return runSafely {
            val settings = settingsStore.settings.first()
            val inputs = if (!settings.anyOn) {
                Inputs(settings)
            } else {
                Inputs(settings, visitRepository.observeAllVisits().first(), seedKeys.first(), program.first())
            }
            apply(inputs)
        }
    }

    /** One run: plans from [inputs] and schedules the plan; false (nothing done) while a backup import runs. */
    private suspend fun apply(inputs: Inputs): Boolean = runs.withLock {
        if (isImporting.value) return@withLock false
        val runtimes = ReminderRuntimes.of(inputs.program, inputs.visits, inputs.seedKeys)
        val plan = ReminderSchedule.plan(inputs.visits.map { it.visit }, inputs.settings, runtimes, clock.instant())
        scheduler.schedule(plan)
        true
    }

    private suspend fun runSafely(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The data couldn't be read: the next change or start tries again. (Nothing is logged: no visit data in logs.)
        false
    }

    companion object {
        /** How long the inputs must stay unchanged before a run (several writes of one save give one run). */
        val DEBOUNCE: Duration = 1.seconds
        private const val RETRIES = 3L
        private val RETRY_DELAY: Duration = 5.seconds
    }
}
