package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramActionResult
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

/**
 * In-memory [ProgramRepository] for ViewModel and screenshot tests, with the real
 * repository's rules:
 *
 * - nothing is "downloaded" unless the permission is [DownloadPermission.ALLOWED];
 * - [refreshIfStale] only without a program, or with one older than 6 hours or
 *   from the future (at [now]); otherwise it clears an old error;
 * - concurrent refreshes share one download;
 * - switching off or deleting during a download (held by [gate]) keeps nothing
 *   from it; a failed download keeps the program;
 * - status is for downloads only; commands return a [ProgramActionResult]
 *   ([nextActionFailure] makes the next one fail).
 *
 * A "download" results in [nextProgram] (stamped with [now]) or, if set,
 * [nextError]. [downloads] counts the downloads that started.
 */
class FakeProgramRepository(
    initial: ProgramState = ProgramState(isLoaded = true),
) : ProgramRepository {

    override val state = MutableStateFlow(initial)

    /** The current time, for staleness and the downloaded program's `fetchedAt`. */
    var now: Instant = SampleProgram.FETCHED_AT

    /** What the next download returns (its `fetchedAt` is replaced by [now]). */
    var nextProgram: Program = SampleProgram.program()

    /** If set, the next downloads fail with this error. */
    var nextError: ProgramError? = null

    /** If set, a download waits for it while in [ProgramStatus.Loading]. */
    var gate: CompletableDeferred<Unit>? = null

    /** If set, the next command ([setDownloadsEnabled], [deleteProgram]) fails with it, once. */
    var nextActionFailure: ProgramError? = null

    var downloads: Int = 0
        private set

    val refreshCalls = mutableListOf<String>()

    private var running: CompletableDeferred<Unit>? = null
    private var generation = 0L

    override suspend fun allowDownloads(): ProgramActionResult = setDownloadsEnabled(true)

    override suspend fun setDownloadsEnabled(enabled: Boolean): ProgramActionResult {
        val permission = if (enabled) DownloadPermission.ALLOWED else DownloadPermission.OFF
        state.update { it.copy(permission = permission) }
        if (!enabled) stopDownload()
        return actionResult()
    }

    override suspend fun deleteProgram(): ProgramActionResult {
        val failure = nextActionFailure
        stopDownload()
        if (failure == null) state.update { it.copy(program = null) }
        return actionResult()
    }

    override fun dismissError() {
        state.update { if (it.status is ProgramStatus.Failed) it.copy(status = ProgramStatus.Idle) else it }
    }

    override suspend fun refreshIfStale() {
        refreshCalls += "refreshIfStale"
        if (running == null && !isStale(state.value.program)) {
            dismissError()
            return
        }
        download()
    }

    override suspend fun refresh() {
        refreshCalls += "refresh"
        download()
    }

    private fun isStale(program: Program?): Boolean {
        if (program == null) return true
        val age = Duration.between(program.fetchedAt, now)
        return age.isNegative || age >= Duration.ofHours(ProgramRepository.STALE_AFTER_HOURS)
    }

    private suspend fun download() {
        if (state.value.permission != DownloadPermission.ALLOWED) return
        running?.let {
            it.await() // single flight: join the running download
            return
        }
        val done = CompletableDeferred<Unit>().also { running = it }
        val mine = ++generation
        downloads++
        state.update { it.copy(status = ProgramStatus.Loading) }
        try {
            gate?.await()
            if (mine != generation) return // stopped meanwhile: keep nothing
            val error = nextError
            state.update {
                if (error != null) {
                    it.copy(status = ProgramStatus.Failed(error))
                } else {
                    it.copy(program = nextProgram.copy(fetchedAt = now), status = ProgramStatus.Idle)
                }
            }
        } finally {
            if (running === done) running = null
            done.complete(Unit)
        }
    }

    private fun stopDownload() {
        generation++
        running?.complete(Unit)
        running = null
        state.update { it.copy(status = ProgramStatus.Idle) }
    }

    private fun actionResult(): ProgramActionResult {
        val failure = nextActionFailure ?: return ProgramActionResult.Success
        nextActionFailure = null
        return ProgramActionResult.Failure(failure)
    }

    companion object {
        /** A repository that has [program] (default: [SampleProgram]) and downloads allowed. */
        fun withProgram(program: Program? = SampleProgram.program()): FakeProgramRepository = FakeProgramRepository(
            ProgramState(permission = DownloadPermission.ALLOWED, program = program, isLoaded = true),
        )
    }
}
