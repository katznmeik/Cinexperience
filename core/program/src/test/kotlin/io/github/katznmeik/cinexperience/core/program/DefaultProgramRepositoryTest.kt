package io.github.katznmeik.cinexperience.core.program

import io.github.katznmeik.cinexperience.core.program.net.FakeTransport
import io.github.katznmeik.cinexperience.core.program.net.ProgramDownloader
import io.github.katznmeik.cinexperience.core.program.net.TransportAbort
import io.github.katznmeik.cinexperience.core.program.net.TransportResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DefaultProgramRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Settings that survive a "restart" (a new repository on the same instance), like shared preferences. */
    private class MemorySettings(@Volatile var permission: DownloadPermission = DownloadPermission.NOT_ASKED) : ProgramSettings {
        var readFailure: Throwable? = null
        var failWrites = false
        val writes: MutableList<DownloadPermission> = java.util.Collections.synchronizedList(mutableListOf())

        override fun readPermission(): DownloadPermission = readFailure?.let { throw it } ?: permission

        override fun writePermission(permission: DownloadPermission): Boolean {
            if (failWrites) return false
            this.permission = permission
            writes += permission
            return true
        }
    }

    private class MutableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(TestPages.NOW)
    private val settings = MemorySettings()
    private val transport = FakeTransport().then { ok(TestPages.fixture) }
    private val cacheFolder: File by lazy { File(temp.root, "files/${ProgramFiles.FOLDER}") }
    private val cacheFile: File get() = File(cacheFolder, ProgramFiles.CACHE_FILE)

    private fun TestScope.repository(cache: ProgramCache = ProgramCache(cacheFolder)) = DefaultProgramRepository(
        settings = settings,
        cache = cache,
        downloader = ProgramDownloader(transport, "1.1.0"),
        parser = ProgramPageParser(clock),
        clock = clock,
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun cachedProgram(fetchedAt: Instant): Program {
        val program = (ProgramPageParser(Clock.fixed(fetchedAt, ZoneOffset.UTC)).parse(TestPages.fixture) as ProgramParseResult.Success).program
        assertTrue(ProgramCache(cacheFolder).write(program))
        return program
    }

    private val ProgramRepository.program: Program? get() = state.value.program
    private val ProgramRepository.status: ProgramStatus get() = state.value.status

    // --- Permission gate ------------------------------------------------------------------------

    @Test
    fun nothingIsDownloadedUnlessAllowed() = runTest {
        for (permission in listOf(DownloadPermission.NOT_ASKED, DownloadPermission.OFF)) {
            settings.permission = permission
            val repository = repository()
            runCurrent() // also runs the repository's own (background) work
            assertTrue(repository.state.value.isLoaded)
            assertEquals(permission, repository.state.value.permission)

            repository.refreshIfStale()
            repository.refresh()
            runCurrent()

            assertEquals(0, transport.requests.size)
            assertNull(repository.program)
            assertEquals(ProgramStatus.Idle, repository.status)
        }
    }

    @Test
    fun theDefaultIsNotAsked() = runTest {
        val repository = repository()
        assertFalse(repository.state.value.isLoaded) // still reading
        runCurrent()
        assertEquals(ProgramState(DownloadPermission.NOT_ASKED, null, ProgramStatus.Idle, isLoaded = true), repository.state.value)
    }

    @Test
    fun theRepositoryNeverDownloadsByItself() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val stale = cachedProgram(TestPages.NOW.minus(Duration.ofDays(1)))
        val repository = repository()
        advanceTimeBy(Duration.ofDays(2).toMillis())
        runCurrent()
        assertEquals(stale, repository.program)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun permissionChangesAreStoredAndReportTheirResult() = runTest {
        val repository = repository()
        assertEquals(ProgramActionResult.Success, repository.allowDownloads())
        assertEquals(DownloadPermission.ALLOWED, settings.permission)
        assertEquals(ProgramActionResult.Success, repository.setDownloadsEnabled(false))
        assertEquals(DownloadPermission.OFF, settings.permission)
        assertEquals(ProgramActionResult.Success, repository.setDownloadsEnabled(true))
        assertEquals(listOf(DownloadPermission.ALLOWED, DownloadPermission.OFF, DownloadPermission.ALLOWED), settings.writes)

        // Off applies even if it can't be stored, and the caller learns about it; the download status is untouched.
        settings.failWrites = true
        val result = repository.setDownloadsEnabled(false)
        assertEquals(ProgramActionResult.Failure(ProgramError.SETTING_NOT_SAVED), result)
        assertEquals(ProgramError.SETTING_NOT_SAVED.message, (result as ProgramActionResult.Failure).message)
        assertEquals(DownloadPermission.OFF, repository.state.value.permission)
        assertEquals(ProgramStatus.Idle, repository.status)
        repository.refresh()
        assertEquals(0, transport.requests.size)
    }

    // --- Downloading ----------------------------------------------------------------------------

    @Test
    fun refreshIfStaleDownloadsWhenThereIsNoProgram() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refreshIfStale()

        val program = checkNotNull(repository.program)
        assertEquals(TestPages.NOW, program.fetchedAt)
        assertEquals(TestPages.NOW, repository.state.value.fetchedAt)
        assertEquals(7, program.showings.size)
        assertEquals(ProgramStatus.Idle, repository.status)
        assertEquals(1, transport.requests.size)
        assertEquals(program, ProgramCache(cacheFolder).read()) // stored
    }

    @Test
    fun refreshIfStaleWaitsSixHours() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refreshIfStale()
        assertEquals(1, transport.requests.size)

        clock.now = TestPages.NOW.plus(Duration.ofHours(6)).minusSeconds(1)
        repository.refreshIfStale()
        assertEquals(1, transport.requests.size)

        clock.now = TestPages.NOW.plus(Duration.ofHours(6))
        repository.refreshIfStale()
        assertEquals(2, transport.requests.size)
        assertEquals(clock.now, repository.state.value.fetchedAt)

        // A program "from the future" (the clock was set back) is stale too.
        clock.now = TestPages.NOW
        repository.refreshIfStale()
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun aFreshCachedProgramIsUsedAndAStaleOneReplaced() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val fresh = cachedProgram(TestPages.NOW.minus(Duration.ofHours(5)))
        val repository = repository()
        repository.refreshIfStale()
        assertEquals(fresh, repository.program)
        assertEquals(0, transport.requests.size)

        cachedProgram(TestPages.NOW.minus(Duration.ofHours(7)))
        val second = repository()
        second.refreshIfStale()
        assertEquals(1, transport.requests.size)
        assertEquals(TestPages.NOW, second.state.value.fetchedAt)
    }

    @Test
    fun refreshDownloadsEvenAFreshProgram() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        cachedProgram(TestPages.NOW.minus(Duration.ofMinutes(5)))
        val repository = repository()
        repository.refresh()
        assertEquals(1, transport.requests.size)
        assertEquals(TestPages.NOW, repository.state.value.fetchedAt)
    }

    @Test
    fun theStatusIsLoadingWhileDownloading() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        val seen = mutableListOf<ProgramStatus>()
        transport.onRequest = { seen += repository.status }
        repository.refresh()
        assertEquals(listOf<ProgramStatus>(ProgramStatus.Loading), seen)
        assertEquals(ProgramStatus.Idle, repository.status)
    }

    @Test
    fun concurrentRefreshesShareOneDownload() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        runCurrent()
        val callers = listOf(
            launch { repository.refreshIfStale() },
            launch { repository.refresh() },
            launch { repository.refresh() },
            launch { repository.refreshIfStale() },
        )
        runCurrent()
        assertTrue(callers.all { it.isCompleted })
        assertEquals(1, transport.requests.size)
        assertNotNull(repository.program)

        // Once it has finished, the next refresh downloads again.
        repository.refresh()
        assertEquals(2, transport.requests.size)
    }

    // --- Failures keep the last good program -----------------------------------------------------

    @Test
    fun failuresKeepTheLastGoodProgram() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refresh()
        val good = checkNotNull(repository.program)
        val stored = cacheFile.readText()

        fun case(error: ProgramError, answer: FakeTransport.(TransportAbort) -> TransportResponse) = answer to error
        val failures = listOf(
            case(ProgramError.OFFLINE) { throw UnknownHostException("www.yorck.de") },
            case(ProgramError.NETWORK) { throw IOException("reset") },
            case(ProgramError.SERVER) { response(503) },
            case(ProgramError.REDIRECT_REFUSED) { response(302, "https://evil.example/") },
            case(ProgramError.UNREADABLE) { ok("<html><body>Maintenance</body></html>") },
            case(ProgramError.UNREADABLE) { ok(TestPages.page("{\"props\":")) },
            case(ProgramError.UNREADABLE) { ok(TestPages.page(emptyList())) },
            case(ProgramError.TOO_LARGE) { ok(tooManyFilms) },
            case(ProgramError.TOO_LARGE) { ok(TestPages.page("[" + List(600_000) { "0" }.joinToString(",") + "]")) },
            case(ProgramError.TOO_LARGE) { response(200, body = FakeTransport.CountingBody(ProgramDownloader.MAX_BYTES + 1)) },
            // Errors, not exceptions, are caught too.
            case(ProgramError.TOO_LARGE) { throw OutOfMemoryError("simulated") },
            case(ProgramError.UNREADABLE) { throw StackOverflowError("simulated") },
        )
        for ((answer, error) in failures) {
            clock.now = clock.now.plusSeconds(60)
            transport.always(answer)
            repository.refresh()
            assertEquals("$error", ProgramStatus.Failed(error), repository.status)
            assertEquals(error.message, (repository.status as ProgramStatus.Failed).message)
            assertSame(good, repository.program)
            assertEquals(stored, cacheFile.readText())
        }

        // The next good download replaces it.
        transport.always { ok(TestPages.fixture) }
        repository.refresh()
        assertEquals(ProgramStatus.Idle, repository.status)
        assertEquals(clock.now, repository.state.value.fetchedAt)
    }

    private val tooManyFilms: String by lazy {
        TestPages.page(List(ProgramRules.MAX_FILMS + 1) { TestPages.film(slug = "film-$it", sessions = emptyList<Any>()) })
    }

    @Test
    fun anErrorCanBeDismissedAndDoesNotOutliveAFreshProgram() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refresh()
        transport.always { throw UnknownHostException("www.yorck.de") }
        repository.refresh()
        assertEquals(ProgramStatus.Failed(ProgramError.OFFLINE), repository.status)

        repository.dismissError()
        assertEquals(ProgramStatus.Idle, repository.status)

        repository.refresh()
        assertEquals(ProgramStatus.Failed(ProgramError.OFFLINE), repository.status)
        // Opening the tab again: the program is fresh, nothing is downloaded, the old error goes.
        repository.refreshIfStale()
        assertEquals(ProgramStatus.Idle, repository.status)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun aProgramThatCantBeSavedIsStillShown() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        File(temp.root, "blocked").writeText("a file where the folder should be")
        val repository = repository(ProgramCache(File(temp.root, "blocked")))
        repository.refresh()
        assertNotNull(repository.program)
        assertEquals(ProgramStatus.Failed(ProgramError.STORAGE), repository.status)
    }

    @Test
    fun anUnreadableStartIsHandledAndTheBrokenCacheDeleted() = runTest {
        settings.readFailure = StackOverflowError("simulated")
        cacheFolder.mkdirs()
        cacheFile.writeText("{ corrupt")
        val repository = repository()
        runCurrent()
        assertEquals(ProgramState(DownloadPermission.NOT_ASKED, null, ProgramStatus.Idle, isLoaded = true), repository.state.value)
        assertFalse(cacheFile.exists())
        // Commands still work.
        assertEquals(ProgramActionResult.Success, repository.deleteProgram())
        assertEquals(ProgramActionResult.Success, repository.allowDownloads())
    }

    // --- Delete ---------------------------------------------------------------------------------

    @Test
    fun deleteProgramRemovesItAndAllowsANewDownload() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refresh()
        assertTrue(cacheFile.exists())

        assertEquals(ProgramActionResult.Success, repository.deleteProgram())
        assertNull(repository.program)
        assertFalse(cacheFile.exists())
        assertEquals(ProgramStatus.Idle, repository.status)
        assertEquals(DownloadPermission.ALLOWED, repository.state.value.permission)

        repository.refreshIfStale()
        assertEquals(2, transport.requests.size)
        assertNotNull(repository.program)
    }

    @Test
    fun aFailedDeleteKeepsTheProgramAndSaysSo() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refresh()
        val program = repository.program
        // Make the file undeletable: a non-empty folder in its place.
        cacheFile.delete()
        File(cacheFile, "x").apply { parentFile!!.mkdirs() }.writeText("x")
        assertEquals(ProgramActionResult.Failure(ProgramError.DELETE_FAILED), repository.deleteProgram())
        assertEquals(ProgramStatus.Idle, repository.status) // not a download status
        assertSame(program, repository.program)
    }

    // --- Stopping a running download (real threads: the transport blocks like a stalled socket) ---

    private fun <T> withThreads(block: suspend (CoroutineScope, CoroutineDispatcher) -> T): T {
        val executor = Executors.newFixedThreadPool(3)
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            return runBlocking { withTimeout(20_000) { block(scope, dispatcher) } }
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private fun blockingRepository(scope: CoroutineScope, dispatcher: CoroutineDispatcher) =
        DefaultProgramRepository(settings, ProgramCache(cacheFolder), ProgramDownloader(transport, "1.1.0"), ProgramPageParser(clock), clock, scope, dispatcher)

    /** Every request blocks until it is aborted; only the 90 s total timeout would end it otherwise. */
    private fun stallEveryRequest() {
        transport.always { abort -> blockUntilAborted(abort) }
    }

    @Test
    fun switchingOffAbortsARunningDownloadAtOnceAndKeepsNothing() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        stallEveryRequest()
        val repository = blockingRepository(scope, dispatcher)
        val refresh = scope.launch { repository.refresh() }
        assertTrue(transport.blocking.await(10, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertEquals(ProgramActionResult.Success, repository.setDownloadsEnabled(false))
        assertTrue("waited for a timeout", (System.nanoTime() - started) / 1_000_000 < 5_000)
        refresh.join()

        assertEquals(1, transport.aborted)
        assertEquals(1, transport.requests.size)
        assertNull(repository.program)
        assertEquals(ProgramStatus.Idle, repository.status)
        assertFalse(cacheFile.exists())
        assertEquals(listOf(DownloadPermission.OFF), settings.writes)
    }

    @Test
    fun offIsStoredEvenIfItsCallerIsCancelled() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        stallEveryRequest()
        val repository = blockingRepository(scope, dispatcher)
        scope.launch { repository.refresh() }
        assertTrue(transport.blocking.await(10, TimeUnit.SECONDS))

        // The Settings screen closes right after the switch is turned off: the command has
        // started (the gate is closed) when its caller is cancelled.
        val caller = scope.launch(start = CoroutineStart.UNDISPATCHED) { repository.setDownloadsEnabled(false) }
        assertEquals(DownloadPermission.OFF, repository.state.value.permission)
        caller.cancel()
        caller.join()
        repository.state.first { it.permission == DownloadPermission.OFF && it.status == ProgramStatus.Idle }
        // Restart: a new repository on the same stored settings.
        val restarted = blockingRepository(scope, dispatcher)
        assertEquals(DownloadPermission.OFF, restarted.state.first { it.isLoaded }.permission)
        assertEquals(DownloadPermission.OFF, settings.permission)
        withTimeout(5_000) { while (transport.aborted == 0) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun deletingDuringADownloadAbortsItAndKeepsNothing() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        stallEveryRequest()
        val repository = blockingRepository(scope, dispatcher)
        val refresh = scope.launch { repository.refresh() }
        assertTrue(transport.blocking.await(10, TimeUnit.SECONDS))

        // Nothing releases the transport but the abort: the delete must cancel the download.
        assertEquals(ProgramActionResult.Success, repository.deleteProgram())
        refresh.join()

        assertEquals(1, transport.aborted)
        assertNull(repository.program)
        assertFalse(cacheFile.exists())
        assertEquals(ProgramStatus.Idle, repository.status)
        assertEquals(DownloadPermission.ALLOWED, repository.state.value.permission)
    }

    @Test
    fun aStoppedDownloadCanNotOverwriteTheState() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        // A stalled connection that, once aborted, still delivers a page (as if the abort came too late).
        transport.always { abort ->
            val released = CountDownLatch(1)
            abort.onAbort { released.countDown() }
            blocking.countDown()
            released.await(10, TimeUnit.SECONDS)
            ok(TestPages.fixture)
        }
        val repository = blockingRepository(scope, dispatcher)
        val refresh = scope.launch { repository.refresh() }
        assertTrue(transport.blocking.await(10, TimeUnit.SECONDS))
        repository.setDownloadsEnabled(false)
        refresh.join()
        assertNull(repository.program)
        assertEquals(ProgramStatus.Idle, repository.status)
        assertFalse(cacheFile.exists())
    }

    @Test
    fun switchingBackOnNeverRunsTwoDownloadsAtOnce() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        transport.always { abort ->
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (requests.size == 1) blockUntilAborted(abort) else ok(TestPages.fixture)
            } finally {
                active.decrementAndGet()
            }
        }
        val repository = blockingRepository(scope, dispatcher)
        scope.launch { repository.refresh() }
        assertTrue(transport.blocking.await(10, TimeUnit.SECONDS))

        // Off, and while it is still stopping the old download, on again and pull to refresh.
        val off = scope.launch { repository.setDownloadsEnabled(false) }
        repository.state.first { it.permission == DownloadPermission.OFF }
        val on = scope.launch {
            repository.setDownloadsEnabled(true)
            repository.refresh()
        }
        off.join()
        on.join()
        assertEquals(1, maxActive.get())
        assertEquals(2, transport.requests.size)
        assertEquals(1, transport.aborted)
        assertEquals(DownloadPermission.ALLOWED, settings.permission)
        assertNotNull(repository.program)
    }

    @Test
    fun aCancelledCallerDoesNotStopTheSharedDownload() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        val release = CountDownLatch(1)
        transport.onRequest = { release.await(10, TimeUnit.SECONDS) }
        val repository = blockingRepository(scope, dispatcher)
        val caller = scope.launch { repository.refresh() } // e.g. a screen that is closed meanwhile
        withTimeout(5_000) { while (transport.requests.isEmpty()) kotlinx.coroutines.delay(10) }
        caller.cancel()
        caller.join()
        release.countDown()
        repository.state.first { it.program != null && it.status == ProgramStatus.Idle }
        assertEquals(1, transport.requests.size)
        assertTrue(cacheFile.exists())
    }
}
