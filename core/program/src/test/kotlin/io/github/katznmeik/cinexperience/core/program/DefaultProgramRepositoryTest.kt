package io.github.katznmeik.cinexperience.core.program

import io.github.katznmeik.cinexperience.core.program.net.FakeTransport
import io.github.katznmeik.cinexperience.core.program.net.ProgramDownloader
import io.github.katznmeik.cinexperience.core.program.net.TransportResponse
import kotlinx.coroutines.CoroutineScope
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

class DefaultProgramRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class MemorySettings(var permission: DownloadPermission = DownloadPermission.NOT_ASKED) : ProgramSettings {
        var failReads = false
        var failWrites = false
        val writes = mutableListOf<DownloadPermission>()

        override fun readPermission(): DownloadPermission = if (failReads) throw IOException("unreadable") else permission

        override fun writePermission(permission: DownloadPermission): Boolean {
            if (failWrites) return false
            this.permission = permission
            writes += permission
            return true
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
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
            runCurrent() // also runs the repository's own (background) work

            assertEquals(0, transport.requests.size)
            assertNull(repository.program)
            assertEquals(ProgramStatus.Idle, repository.status)
        }
    }

    @Test
    fun theDefaultIsNotAsked() = runTest {
        val repository = repository()
        assertFalse(repository.state.value.isLoaded) // still reading
        runCurrent() // also runs the repository's own (background) work
        assertEquals(ProgramState(DownloadPermission.NOT_ASKED, null, ProgramStatus.Idle, isLoaded = true), repository.state.value)
    }

    @Test
    fun theRepositoryNeverDownloadsByItself() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val stale = cachedProgram(TestPages.NOW.minus(Duration.ofDays(1)))
        val repository = repository()
        advanceTimeBy(Duration.ofDays(2).toMillis())
        runCurrent() // also runs the repository's own (background) work
        assertEquals(stale, repository.program)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun theGrantedPermissionIsStoredAndOffIsHonouredEvenIfItCantBeStored() = runTest {
        val repository = repository()
        repository.allowDownloads()
        assertEquals(DownloadPermission.ALLOWED, settings.permission)
        repository.setDownloadsEnabled(false)
        assertEquals(DownloadPermission.OFF, settings.permission)
        repository.setDownloadsEnabled(true)
        assertEquals(listOf(DownloadPermission.ALLOWED, DownloadPermission.OFF, DownloadPermission.ALLOWED), settings.writes)

        settings.failWrites = true
        repository.setDownloadsEnabled(false)
        assertEquals(DownloadPermission.OFF, repository.state.value.permission)
        assertEquals(ProgramStatus.Failed(ProgramError.SETTING_NOT_SAVED), repository.status)
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
        assertEquals(6, program.showings.size)
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
        runCurrent() // also runs the repository's own (background) work
        val callers = listOf(
            launch { repository.refreshIfStale() },
            launch { repository.refresh() },
            launch { repository.refresh() },
            launch { repository.refreshIfStale() },
        )
        runCurrent() // also runs the repository's own (background) work
        assertTrue(callers.all { it.isCompleted })
        assertEquals(1, transport.requests.size)
        assertNotNull(repository.program)

        // Once it has finished, the next refresh downloads again.
        repository.refresh()
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun aCancelledCallerDoesNotStopTheSharedDownload() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.onRequest = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val repository = blockingRepository(scope, dispatcher)
        val caller = scope.launch { repository.refresh() } // e.g. a screen that is closed meanwhile
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        caller.cancel()
        caller.join()
        release.countDown()
        repository.state.first { it.program != null && it.status == ProgramStatus.Idle }
        assertEquals(1, transport.requests.size)
        assertTrue(cacheFile.exists())
    }

    // --- Failures keep the last good program -----------------------------------------------------

    @Test
    fun failuresKeepTheLastGoodProgram() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refresh()
        val good = checkNotNull(repository.program)
        val stored = cacheFile.readText()

        fun case(error: ProgramError, answer: FakeTransport.() -> TransportResponse) = answer to error
        val failures = listOf(
            case(ProgramError.OFFLINE) { throw UnknownHostException("www.yorck.de") },
            case(ProgramError.NETWORK) { throw IOException("reset") },
            case(ProgramError.SERVER) { response(503) },
            case(ProgramError.REDIRECT_REFUSED) { response(302, "https://evil.example/") },
            case(ProgramError.UNREADABLE) { ok("<html><body>Maintenance</body></html>") },
            case(ProgramError.UNREADABLE) { ok(TestPages.page("{\"props\":")) },
            case(ProgramError.UNREADABLE) { ok(TestPages.page(emptyList())) },
            case(ProgramError.TOO_LARGE) { ok(tooManyFilms) },
            case(ProgramError.TOO_LARGE) { ok(TestPages.page("[" + List(800_000) { "0" }.joinToString(",") + "]")) },
            case(ProgramError.TOO_LARGE) { response(200, body = FakeTransport.CountingBody(ProgramDownloader.MAX_BYTES + 1)) },
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
    fun aProgramThatCantBeSavedIsStillShown() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        File(temp.root, "blocked").writeText("a file where the folder should be")
        val repository = repository(ProgramCache(File(temp.root, "blocked")))
        repository.refresh()
        assertNotNull(repository.program)
        assertEquals(ProgramStatus.Failed(ProgramError.STORAGE), repository.status)
    }

    @Test
    fun anUnreadableStartIsHandled() = runTest {
        settings.failReads = true
        cacheFolder.mkdirs()
        cacheFile.writeText("{ corrupt")
        val repository = repository()
        runCurrent() // also runs the repository's own (background) work
        assertEquals(ProgramState(DownloadPermission.NOT_ASKED, null, ProgramStatus.Idle, isLoaded = true), repository.state.value)
    }

    // --- Delete ---------------------------------------------------------------------------------

    @Test
    fun deleteProgramRemovesItAndAllowsANewDownload() = runTest {
        settings.permission = DownloadPermission.ALLOWED
        val repository = repository()
        repository.refresh()
        assertTrue(cacheFile.exists())

        repository.deleteProgram()
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
        repository.deleteProgram()
        assertEquals(ProgramStatus.Failed(ProgramError.DELETE_FAILED), repository.status)
        assertSame(program, repository.program)
    }

    // --- Stopping a running download (real threads: the transport blocks) ----------------------

    private fun <T> withThreads(block: suspend (CoroutineScope, kotlinx.coroutines.CoroutineDispatcher) -> T): T {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            return runBlocking { withTimeout(20_000) { block(scope, dispatcher) } }
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private fun blockingRepository(scope: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        DefaultProgramRepository(settings, ProgramCache(cacheFolder), ProgramDownloader(transport, "1.1.0"), ProgramPageParser(clock), clock, scope, dispatcher)

    @Test
    fun switchingOffStopsARunningDownloadAndKeepsNothing() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.always { response(302, "/en/films/") }.then { ok(TestPages.fixture) }
        transport.onRequest = { number ->
            if (number == 1) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val repository = blockingRepository(scope, dispatcher)
        val refresh = scope.launch { repository.refresh() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val off = scope.launch { repository.setDownloadsEnabled(false) }
        repository.state.first { it.permission == DownloadPermission.OFF } // the gate is closed at once
        release.countDown()
        off.join()
        refresh.join()

        assertEquals(1, transport.requests.size) // the redirect was not followed
        assertNull(repository.program)
        assertEquals(ProgramStatus.Idle, repository.status)
        assertFalse(cacheFile.exists())
        assertEquals(listOf(DownloadPermission.OFF), settings.writes)
    }

    @Test
    fun deletingDuringADownloadKeepsNothing() = withThreads { scope, dispatcher ->
        settings.permission = DownloadPermission.ALLOWED
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.onRequest = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val repository = blockingRepository(scope, dispatcher)
        val refresh = scope.launch { repository.refresh() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val delete = scope.launch { repository.deleteProgram() }
        release.countDown()
        delete.join()
        refresh.join()

        assertNull(repository.program)
        assertFalse(cacheFile.exists())
        assertEquals(ProgramStatus.Idle, repository.status)
        assertEquals(DownloadPermission.ALLOWED, repository.state.value.permission)
    }
}
