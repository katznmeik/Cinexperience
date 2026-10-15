package io.github.katznmeik.cinexperience.core.program

import android.content.Context
import io.github.katznmeik.cinexperience.core.program.net.DownloadResult
import io.github.katznmeik.cinexperience.core.program.net.HttpsProgramTransport
import io.github.katznmeik.cinexperience.core.program.net.ProgramDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration

/**
 * The app's [ProgramRepository] (spec §11.1).
 *
 * - **Startup:** reads the stored permission and the cached program in [scope]
 *   ([ProgramState.isLoaded]); never downloads by itself.
 * - **Gate:** a download starts only while the permission is
 *   [DownloadPermission.ALLOWED], and the downloader asks again before every
 *   request, so switching downloads off stops even a redirect chain.
 * - **Single flight:** one download at a time, run in [scope] so it isn't tied to
 *   one screen; concurrent [refresh] / [refreshIfStale] calls wait for it.
 * - **Failures** never throw: they become [ProgramStatus.Failed], and the last good
 *   program stays (also in the cache, which is replaced only by a new program that
 *   was parsed and checked).
 * - Switching off and [deleteProgram] cancel a running download and wait for it.
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

    /** Serialises permission changes, deletion and starting a download. */
    private val commands = Mutex()

    /** The running download, if any (guarded by [commands]). */
    private var download: Deferred<Unit>? = null

    private val loading: Deferred<Unit> = scope.async(ioDispatcher, start = CoroutineStart.LAZY) { load() }

    init {
        loading.start()
    }

    private fun load() {
        val permission = try {
            settings.readPermission()
        } catch (e: Exception) {
            DownloadPermission.NOT_ASKED // fails closed: no downloads
        }
        val program = cache.read()
        mutableState.update { it.copy(permission = permission, program = program, isLoaded = true) }
    }

    override suspend fun allowDownloads() = setPermission(DownloadPermission.ALLOWED)

    override suspend fun setDownloadsEnabled(enabled: Boolean) =
        setPermission(if (enabled) DownloadPermission.ALLOWED else DownloadPermission.OFF)

    private suspend fun setPermission(permission: DownloadPermission) {
        loading.await()
        commands.withLock {
            // In memory first: the downloader's gate closes at once.
            mutableState.update { it.copy(permission = permission) }
            if (permission != DownloadPermission.ALLOWED) {
                download?.cancelAndJoin()
                download = null
                mutableState.update { it.copy(status = ProgramStatus.Idle) }
            }
            val saved = withContext(ioDispatcher) {
                try {
                    settings.writePermission(permission)
                } catch (e: Exception) {
                    false
                }
            }
            if (!saved) mutableState.update { it.copy(status = ProgramStatus.Failed(ProgramError.SETTING_NOT_SAVED)) }
        }
    }

    override suspend fun refreshIfStale() = startOrJoinDownload(onlyIfStale = true)

    override suspend fun refresh() = startOrJoinDownload(onlyIfStale = false)

    private suspend fun startOrJoinDownload(onlyIfStale: Boolean) {
        loading.await()
        val job = commands.withLock {
            val current = mutableState.value
            if (current.permission != DownloadPermission.ALLOWED) return
            download?.takeIf { it.isActive } ?: run {
                if (onlyIfStale && !isStale(current.program)) return
                scope.async(ioDispatcher) { downloadAndStore() }.also { download = it }
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

    private suspend fun downloadAndStore() {
        mutableState.update { it.copy(status = ProgramStatus.Loading) }
        val status = try {
            fetchParseAndStore()
        } catch (e: CancellationException) {
            mutableState.update { it.copy(status = ProgramStatus.Idle) }
            throw e
        } catch (e: Exception) {
            ProgramStatus.Failed(ProgramError.NETWORK) // unexpected; the last good program stays
        }
        mutableState.update { it.copy(status = status) }
    }

    private suspend fun fetchParseAndStore(): ProgramStatus {
        val result = downloader.download(isAllowed = { mutableState.value.permission == DownloadPermission.ALLOWED })
        val page = when (result) {
            is DownloadResult.Success -> result.page
            is DownloadResult.Failure -> return ProgramStatus.Failed(result.error)
            DownloadResult.NotAllowed -> return ProgramStatus.Idle
        }
        val parsed = when (val outcome = parser.parse(page)) {
            is ProgramParseResult.Success -> outcome.program
            is ProgramParseResult.Failure -> return ProgramStatus.Failed(outcome.reason.toError())
        }
        currentCoroutineContext().ensureActive() // switched off or deleted meanwhile: keep nothing
        val stored = cache.write(parsed)
        mutableState.update { it.copy(program = parsed) }
        return if (stored) ProgramStatus.Idle else ProgramStatus.Failed(ProgramError.STORAGE)
    }

    override suspend fun deleteProgram() {
        loading.await()
        commands.withLock {
            download?.cancelAndJoin()
            download = null
            val deleted = withContext(ioDispatcher) { cache.delete() }
            mutableState.update {
                if (deleted) {
                    it.copy(program = null, status = ProgramStatus.Idle)
                } else {
                    it.copy(status = ProgramStatus.Failed(ProgramError.DELETE_FAILED))
                }
            }
        }
    }

    companion object {
        /**
         * The repository for the app: the cache in `filesDir/program/`, the permission
         * in shared preferences, the real HTTPS transport. Reading the stored state
         * starts at once in [scope]; nothing is downloaded until [refreshIfStale] or
         * [refresh] is called with the permission granted.
         */
        fun create(
            context: Context,
            appVersion: String,
            scope: CoroutineScope,
            clock: Clock = Clock.systemUTC(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ProgramRepository {
            val appContext = context.applicationContext
            return DefaultProgramRepository(
                settings = SharedPreferencesProgramSettings(
                    appContext.getSharedPreferences(SharedPreferencesProgramSettings.PREFERENCES_NAME, Context.MODE_PRIVATE),
                ),
                cache = ProgramCache(File(appContext.filesDir, ProgramFiles.FOLDER)),
                downloader = ProgramDownloader(HttpsProgramTransport(), appVersion),
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
