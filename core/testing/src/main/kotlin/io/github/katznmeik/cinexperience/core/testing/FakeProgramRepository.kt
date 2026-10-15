package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.Program
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
 * repository's rules: nothing is "downloaded" unless the permission is
 * [DownloadPermission.ALLOWED], [refreshIfStale] only without a program or with
 * one older than 6 hours at [now], a failed download keeps the program.
 *
 * A "download" results in [nextProgram] (stamped with [now]) or, if set,
 * [nextError]; [gate] holds it in [ProgramStatus.Loading] until completed.
 * [downloads] counts the downloads that ran.
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

    var downloads: Int = 0
        private set

    val refreshCalls = mutableListOf<String>()

    override suspend fun allowDownloads() {
        state.update { it.copy(permission = DownloadPermission.ALLOWED) }
    }

    override suspend fun setDownloadsEnabled(enabled: Boolean) {
        state.update {
            if (enabled) {
                it.copy(permission = DownloadPermission.ALLOWED)
            } else {
                it.copy(permission = DownloadPermission.OFF, status = ProgramStatus.Idle)
            }
        }
    }

    override suspend fun refreshIfStale() {
        refreshCalls += "refreshIfStale"
        val program = state.value.program
        val stale = program == null ||
            Duration.between(program.fetchedAt, now) >= Duration.ofHours(ProgramRepository.STALE_AFTER_HOURS)
        if (stale) download()
    }

    override suspend fun refresh() {
        refreshCalls += "refresh"
        download()
    }

    private suspend fun download() {
        if (state.value.permission != DownloadPermission.ALLOWED) return
        downloads++
        state.update { it.copy(status = ProgramStatus.Loading) }
        gate?.await()
        val error = nextError
        state.update {
            if (error != null) {
                it.copy(status = ProgramStatus.Failed(error))
            } else {
                it.copy(program = nextProgram.copy(fetchedAt = now), status = ProgramStatus.Idle)
            }
        }
    }

    override suspend fun deleteProgram() {
        state.update { it.copy(program = null, status = ProgramStatus.Idle) }
    }

    companion object {
        /** A repository that has [program] (default: [SampleProgram]) and downloads allowed. */
        fun withProgram(program: Program? = SampleProgram.program()): FakeProgramRepository = FakeProgramRepository(
            ProgramState(permission = DownloadPermission.ALLOWED, program = program, isLoaded = true),
        )
    }
}
