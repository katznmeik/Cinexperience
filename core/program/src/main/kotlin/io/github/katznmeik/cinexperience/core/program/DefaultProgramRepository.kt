package io.github.katznmeik.cinexperience.core.program

import android.content.Context
import io.github.katznmeik.cinexperience.core.program.net.DownloadResult
import io.github.katznmeik.cinexperience.core.program.net.HttpsProgramTransport
import io.github.katznmeik.cinexperience.core.program.net.ProgramDownloader
import io.github.katznmeik.cinexperience.core.program.net.ProgramTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's [ProgramRepository] (spec §11.1). Create one per process
 * (`AppContainer` does): two instances would download and write the same cache
 * independently.
 *
 * - **Startup:** reads the stored permission and the cached program in [scope]
 *   ([ProgramState.isLoaded]); never downloads by itself. An unreadable cache is
 *   deleted and counts as none.
 * - **Gate:** a download starts only while the permission is
 *   [DownloadPermission.ALLOWED], and the downloader asks again before every
 *   request, so switching downloads off stops even a redirect chain.
 * - **Single flight:** one download at a time, run in [scope] so it isn't tied to
 *   one screen; concurrent [refresh] / [refreshIfStale] calls wait for it. A
 *   stopped download is waited for before a new one starts.
 * - **Commands** (permission changes, delete) run under one lock and to the end
 *   even if their caller is cancelled. Off is stored first; then a running
 *   download is cancelled, which aborts its blocked network call at once.
 * - **Status:** only the current download writes [ProgramStatus]; a stopped one
 *   can't overwrite a newer state (each download has a generation number). One
 *   stopped while it writes the cache sets the cache back to the program shown.
 * - **Failures** never throw: they become [ProgramStatus.Failed] (downloads) or
 *   [ProgramActionResult.Failure] (commands), and the last good program stays
 *   (also in the cache, which is replaced only by a new program that was parsed
 *   and checked).
 */
class DefaultProgramRepository internal constructor(
    private val settings: ProgramSettings,
    private val cache: ProgramCache,
    private val downloader: ProgramDownloader,
    private val parser: ProgramPageParser,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : ProgramRepository {

    private val mutableState = MutableStateFlow(ProgramState())
    override val state: StateFlow<ProgramState> = mutableState.asStateFlow()

    /** Serialises commands and starting a download. */
    private val commands = Mutex()

    /** The running (or last) download (guarded by [commands]). */
    private var download: Deferred<Unit>? = null

    /** The generation of the current download; anything older may not write the state. */
    private val generation = AtomicLong(0)

    /** True while [deleteProgram] stops a download: the cache goes anyway, so it isn't set back first. */
    @Volatile
    private var deleting = false

    /** Set by a download that [deleteProgram] stopped after it wrote the cache: the file holds its program. */
    @Volatile
    private var leftForDelete = false

    private val loading: Job = scope.launch(ioDispatcher) { load() }

    private fun load() {
        val permission = try {
            settings.readPermission()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            DownloadPermission.NOT_ASKED // fails closed: no downloads
        }
        val program = try {
            cache.read()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null // e.g. out of memory on a damaged file
        }
        if (program == null) cache.delete() // nothing usable: an unreadable file goes
        mutableState.update { it.copy(permission = permission, program = program, isLoaded = true) }
    }

    /** Runs [block] after startup, under the command lock, to the end even if the caller is cancelled. */
    private suspend fun <T> command(block: suspend () -> T): T = withContext(NonCancellable) {
        loading.join()
        commands.withLock { block() }
    }

    override suspend fun allowDownloads(): ProgramActionResult = setDownloadsEnabled(true)

    override suspend fun setDownloadsEnabled(enabled: Boolean): ProgramActionResult = command {
        val permission = if (enabled) DownloadPermission.ALLOWED else DownloadPermission.OFF
        // In memory first: the downloader's gate closes at once. Then stored, then stopped.
        mutableState.update { it.copy(permission = permission) }
        val saved = withContext(ioDispatcher) {
            try {
                settings.writePermission(permission)
            } catch (e: Exception) {
                false
            }
        }
        if (permission != DownloadPermission.ALLOWED) stopDownload()
        if (saved) ProgramActionResult.Success else ProgramActionResult.Failure(ProgramError.SETTING_NOT_SAVED)
    }

    override suspend fun deleteProgram(): ProgramActionResult = command {
        deleting = true
        try {
            stopDownload()
        } finally {
            deleting = false
        }
        val deleted = withContext(ioDispatcher) { cache.delete() }
        val leftOver = leftForDelete.also { leftForDelete = false }
        if (deleted) {
            mutableState.update { it.copy(program = null) }
            ProgramActionResult.Success
        } else {
            // The file may hold the program of the download stopped here: back to the one shown.
            if (leftOver) withContext(ioDispatcher) { restoreCache(mutableState.value.program) }
            ProgramActionResult.Failure(ProgramError.DELETE_FAILED)
        }
    }

    /** Cancels a running download (aborting its network call), waits for it, and resets the status. */
    private suspend fun stopDownload() {
        generation.incrementAndGet() // the stopped download may not write any more
        download?.cancelAndJoin()
        download = null
        mutableState.update { it.copy(status = ProgramStatus.Idle) }
    }

    override fun dismissError() {
        mutableState.update { if (it.status is ProgramStatus.Failed) it.copy(status = ProgramStatus.Idle) else it }
    }

    override suspend fun refreshIfStale() = startOrJoinDownload(onlyIfStale = true)

    override suspend fun refresh() = startOrJoinDownload(onlyIfStale = false)

    private suspend fun startOrJoinDownload(onlyIfStale: Boolean) {
        loading.join()
        val job = commands.withLock {
            if (mutableState.value.permission != DownloadPermission.ALLOWED) return
            val running = download
            if (running != null && running.isActive) {
                running
            } else {
                running?.join() // a stopped download still finishing: never two at once
                if (onlyIfStale && !isStale(mutableState.value.program)) {
                    dismissError() // the program is fresh: an old error no longer applies
                    return
                }
                val current = generation.incrementAndGet()
                mutableState.update { it.copy(status = ProgramStatus.Loading) }
                scope.async(ioDispatcher) { downloadAndStore(current) }.also { download = it }
            }
        }
        try {
            job.await()
        } catch (e: CancellationException) {
            // The download was stopped (switched off, deleted); only pass on our own cancellation.
            currentCoroutineContext().ensureActive()
        }
    }

    private fun isStale(program: Program?): Boolean {
        if (program == null) return true
        val age = Duration.between(program.fetchedAt, clock.instant())
        // A program "from the future" (the clock was set back) counts as stale too.
        return age.isNegative || age >= Duration.ofHours(ProgramRepository.STALE_AFTER_HOURS)
    }

    /** Applies [transform] only while [generation] is still the current download's; returns whether it did. */
    private fun updateIfCurrent(generation: Long, transform: (ProgramState) -> ProgramState): Boolean {
        var applied = false
        mutableState.update { state ->
            applied = this.generation.get() == generation
            if (applied) transform(state) else state
        }
        return applied
    }

    private suspend fun downloadAndStore(generation: Long) {
        val status = try {
            fetchParseAndStore(generation)
        } catch (e: CancellationException) {
            throw e // whoever stopped it sets the status
        } catch (e: OutOfMemoryError) {
            ProgramStatus.Failed(ProgramError.TOO_LARGE)
        } catch (t: Throwable) {
            ProgramStatus.Failed(ProgramError.UNREADABLE) // unexpected; the last good program stays
        }
        updateIfCurrent(generation) { it.copy(status = status) }
    }

    private suspend fun fetchParseAndStore(generation: Long): ProgramStatus {
        val result = downloader.download(isAllowed = {
            mutableState.value.permission == DownloadPermission.ALLOWED && this.generation.get() == generation
        })
        val page = when (result) {
            is DownloadResult.Success -> result.page
            is DownloadResult.Failure -> return ProgramStatus.Failed(result.error)
            DownloadResult.NotAllowed -> return ProgramStatus.Idle
        }
        val parsed = when (val outcome = parser.parse(page)) {
            is ProgramParseResult.Success -> outcome.program
            is ProgramParseResult.Failure -> return ProgramStatus.Failed(outcome.reason.toError())
        }
        // Switched off or deleted meanwhile: keep nothing.
        currentCoroutineContext().ensureActive()
        if (this.generation.get() != generation) return ProgramStatus.Idle
        val stored = cache.write(parsed)
        if (!updateIfCurrent(generation) { it.copy(program = parsed) }) {
            // Stopped while the file was written: the cache goes back to the program that is
            // shown (the stop waits for this download, so nothing else changes it meanwhile),
            // unless the stop is a delete, which removes the file next (or, if that fails,
            // sets it back itself).
            if (stored) {
                if (deleting) leftForDelete = true else restoreCache(mutableState.value.program)
            }
            return ProgramStatus.Idle
        }
        return if (stored) ProgramStatus.Idle else ProgramStatus.Failed(ProgramError.STORAGE)
    }

    /** Writes [program] into the cache again, or removes the cache if there is none (or the write fails). */
    private fun restoreCache(program: Program?) {
        if (program == null || !cache.write(program)) cache.delete()
    }

    companion object {
        /**
         * The repository for the app: the cache in `filesDir/program/`, the permission
         * in shared preferences, the HTTPS transport. Reading the stored state starts at
         * once in [scope]; nothing is downloaded until [refreshIfStale] or [refresh] is
         * called with the permission granted. Call it once per process.
         *
         * @param transport only for tests, which pass one that never reaches the network.
         */
        fun create(
            context: Context,
            appVersion: String,
            scope: CoroutineScope,
            clock: Clock = Clock.systemUTC(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            transport: ProgramTransport = HttpsProgramTransport(),
        ): ProgramRepository {
            val appContext = context.applicationContext
            return DefaultProgramRepository(
                settings = SharedPreferencesProgramSettings(
                    appContext.getSharedPreferences(SharedPreferencesProgramSettings.PREFERENCES_NAME, Context.MODE_PRIVATE),
                ),
                cache = ProgramCache(File(appContext.filesDir, ProgramFiles.FOLDER)),
                downloader = ProgramDownloader(transport, appVersion),
                parser = ProgramPageParser(clock),
                clock = clock,
                scope = scope,
                ioDispatcher = ioDispatcher,
            )
        }

        internal fun ProgramParseFailure.toError(): ProgramError = when (this) {
            ProgramParseFailure.TOO_MANY_FILMS,
            ProgramParseFailure.TOO_MANY_SHOWINGS,
            ProgramParseFailure.TOO_COMPLEX,
            -> ProgramError.TOO_LARGE
            ProgramParseFailure.NO_DATA_BLOCK,
            ProgramParseFailure.INVALID_JSON,
            ProgramParseFailure.UNEXPECTED_STRUCTURE,
            ProgramParseFailure.NOTHING_VALID,
            -> ProgramError.UNREADABLE
        }
    }
}
