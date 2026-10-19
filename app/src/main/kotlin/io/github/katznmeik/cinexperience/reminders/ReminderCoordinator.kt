package io.github.katznmeik.cinexperience.reminders

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.data.settings.ReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.ReminderSchedule
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
 * off, the visits and the program aren't observed at all (the plan is empty). Nothing
 * here throws into the caller's scope: a failing repository is tried again, also an
 * `Error` such as out of memory is caught; only cancellation ends it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
     * `AppContainer.onAppStart`): a change after a quiet time is planned at once (so a
     * save followed by closing the app still sets its alarms), further changes within
     * [debounce] give one more run once they have settled; never while a backup import
     * runs. A failing repository is tried again without limit, after a growing pause
     * (5 s, doubled up to [MAX_RETRY_DELAY]); once data arrives again, the pause starts
     * at 5 s again.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        var failures = 0L
        while (true) {
            try {
                combine(inputs, isImporting) { inputs, importing -> inputs.takeUnless { importing } }
                    .leadingDebounce(debounce)
                    .buffer(Channel.CONFLATED)
                    .collect { inputs ->
                        // Data arrived: the repositories work (again).
                        failures = 0
                        if (inputs != null) runSafely { apply(inputs) }
                    }
                return@launch // The inputs never end; if they did, there would be nothing more to plan.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Even an Error: tried again after a pause, never thrown into the app's scope.
                delay(retryDelay(failures++))
            }
        }
    }

    /** [reminder] was posted: no plan sets its alarm again ([ReminderScheduler.markPosted]). Never throws. */
    fun markPosted(reminder: PlannedReminder) {
        try {
            scheduler.markPosted(reminder)
        } catch (e: Throwable) {
            // At worst the alarm is set again and its fire-time check decides.
        }
    }

    /**
     * After planning failed (or took too long) in a receiver: a check-in in
     * [RETRY_AFTER] plans again. Never throws.
     */
    fun scheduleRetry() {
        try {
            val now = clock.instant()
            scheduler.scheduleRetry(now.plus(RETRY_AFTER), now)
        } catch (e: Throwable) {
            // The next start or change plans again.
        }
    }

    /**
     * Schedules from the current data once; false if that wasn't possible (a backup
     * import runs: the observer started by [start] schedules once it has ended, or the
     * data couldn't be read; also an `Error`). Never throws, except cancellation of the caller.
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
        val now = clock.instant()
        val plan = ReminderSchedule.plan(inputs.visits.map { it.visit }, inputs.settings, runtimes, now)
        scheduler.schedule(plan, now)
        true
    }

    private suspend fun runSafely(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // The data couldn't be read (even an Error such as out of memory): the next change or
        // start tries again. (Nothing is logged: no visit data in logs.)
        false
    }

    companion object {
        /** Further changes within this time after a run give one more run once they have settled. */
        val DEBOUNCE: Duration = 1.seconds

        /** The longest pause before a failing repository is tried again. */
        val MAX_RETRY_DELAY: Duration = 5.minutes

        /** A receiver whose planning failed tries again with a check-in this much later. */
        val RETRY_AFTER: java.time.Duration = java.time.Duration.ofMinutes(15)

        private val FIRST_RETRY_DELAY: Duration = 5.seconds

        /** 5 s, 10 s, 20 s, … at most [MAX_RETRY_DELAY]. */
        internal fun retryDelay(attempt: Long): Duration =
            if (attempt >= 6) MAX_RETRY_DELAY else minOf(FIRST_RETRY_DELAY * (1 shl attempt.toInt()), MAX_RETRY_DELAY)
    }
}

/**
 * The first value after a quiet time at once (leading edge); values that follow within
 * [window] are collected, and the last of them is emitted once [window] has passed without
 * a new one (trailing edge). A value and the end of a window that come at the same moment
 * lose nothing: `select` takes the value first, as kotlinx's `debounce` does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.leadingDebounce(window: Duration): Flow<T> = channelFlow {
    val values = Channel<T>(Channel.CONFLATED)
    launch {
        this@leadingDebounce.collect { values.send(it) }
        values.close()
    }
    // A value waiting for its window to pass; NONE if there is none (null is a value here: a backup import runs).
    var pending: Any? = NONE
    // No value within the last window: the next one goes out at once.
    var quiet = true
    while (true) {
        val ended = select {
            values.onReceiveCatching { result ->
                if (result.isClosed) {
                    @Suppress("UNCHECKED_CAST")
                    if (pending !== NONE) send(pending as T)
                    true
                } else {
                    val value = result.getOrThrow()
                    if (quiet) send(value) else pending = value
                    quiet = false
                    false
                }
            }
            if (!quiet) {
                onTimeout(window) {
                    if (pending !== NONE) {
                        @Suppress("UNCHECKED_CAST")
                        send(pending as T)
                        pending = NONE
                    } else {
                        quiet = true
                    }
                    false
                }
            }
        }
        if (ended) return@channelFlow
    }
}

/** No value waiting ([leadingDebounce]). */
private object NONE
