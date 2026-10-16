package io.github.katznmeik.cinexperience.core.program

import io.github.katznmeik.cinexperience.core.program.net.FakeTransport
import io.github.katznmeik.cinexperience.core.program.net.ProgramDownloader
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Switching downloads off, or deleting the program, while a download is writing
 * its program into the cache: afterwards the cache holds what the app shows (the
 * stopped download keeps nothing), also after a restart.
 *
 * Deterministic, on one test thread: the cache's folder runs a hook when the cache
 * starts writing (after the repository's last check before the write). The hook
 * starts the command on the repository's IO dispatcher, undispatched, so it runs
 * right there until it waits for the download to finish.
 */
class ProgramWriteRaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class MemorySettings(@Volatile var permission: DownloadPermission) : ProgramSettings {
        override fun readPermission(): DownloadPermission = permission

        override fun writePermission(permission: DownloadPermission): Boolean {
            this.permission = permission
            return true
        }
    }

    /**
     * The cache folder. [ProgramCache.write] looks at its folder (`isDirectory`) before
     * it writes anything, and nothing else does: [writes] counts the writes, and
     * [onWrite] runs at the next one, once.
     */
    private class WatchedFolder(path: String) : File(path) {
        @Volatile
        var onWrite: (() -> Unit)? = null

        @Volatile
        var writes = 0

        override fun isDirectory(): Boolean {
            writes++
            onWrite?.also { onWrite = null }?.invoke()
            return super.isDirectory()
        }
    }

    private val settings = MemorySettings(DownloadPermission.ALLOWED)
    private val transport = FakeTransport().then { ok(TestPages.fixture) }
    private val cacheFolder: File by lazy { File(temp.root, "files/${ProgramFiles.FOLDER}") }
    private val folder: WatchedFolder by lazy { WatchedFolder(cacheFolder.path) }
    private val cacheFile: File get() = File(cacheFolder, ProgramFiles.CACHE_FILE)

    private fun TestScope.repository(io: TestDispatcher) = DefaultProgramRepository(
        settings = settings,
        cache = ProgramCache(folder),
        downloader = ProgramDownloader(transport, "1.1.0"),
        parser = ProgramPageParser(TestPages.clock),
        clock = TestPages.clock,
        scope = backgroundScope,
        ioDispatcher = io,
    )

    /** A program from an earlier download, in the cache. */
    private fun cachedProgram(fetchedAt: Instant): Program {
        val program = (ProgramPageParser(Clock.fixed(fetchedAt, ZoneOffset.UTC)).parse(TestPages.fixture) as ProgramParseResult.Success).program
        assertTrue(ProgramCache(cacheFolder).write(program))
        return program
    }

    /** Runs [command] when the download starts writing its program, as described above. */
    private fun TestScope.whenTheProgramIsWritten(io: TestDispatcher, command: suspend () -> ProgramActionResult): () -> ProgramActionResult? {
        var result: ProgramActionResult? = null
        folder.onWrite = {
            backgroundScope.launch(io, start = CoroutineStart.UNDISPATCHED) { result = command() }
        }
        return { result }
    }

    @Test
    fun switchingOffWhileTheProgramIsWrittenKeepsTheShownOne() = runTest {
        val shown = cachedProgram(TestPages.NOW.minus(Duration.ofHours(7)))
        val io = StandardTestDispatcher(testScheduler)
        val repository = repository(io)
        runCurrent()
        assertEquals(shown, repository.state.value.program)

        val result = whenTheProgramIsWritten(io) { repository.setDownloadsEnabled(false) }
        repository.refresh()
        runCurrent()

        assertNull("the command ran while the program was written", folder.onWrite)
        assertEquals(ProgramActionResult.Success, result())
        assertEquals(1, transport.requests.size)
        assertEquals(DownloadPermission.OFF, settings.permission)
        val state = repository.state.value
        assertEquals(DownloadPermission.OFF, state.permission)
        assertEquals(ProgramStatus.Idle, state.status)
        assertEquals(shown, state.program)
        // The file holds the same program (written again), so a restart shows it too.
        assertEquals(shown, ProgramCache(cacheFolder).read())
        assertEquals(2, folder.writes)
    }

    @Test
    fun switchingOffWhileTheFirstProgramIsWrittenKeepsNone() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val repository = repository(io)
        runCurrent()

        val result = whenTheProgramIsWritten(io) { repository.setDownloadsEnabled(false) }
        repository.refresh()
        runCurrent()

        assertNull(folder.onWrite)
        assertEquals(ProgramActionResult.Success, result())
        assertNull(repository.state.value.program)
        assertEquals(ProgramStatus.Idle, repository.state.value.status)
        assertFalse(cacheFile.exists())
        assertEquals(emptyList<String>(), cacheFolder.list().orEmpty().toList()) // no temporary file either
    }

    @Test
    fun deletingWhileTheProgramIsWrittenKeepsNone() = runTest {
        cachedProgram(TestPages.NOW.minus(Duration.ofHours(7)))
        val io = StandardTestDispatcher(testScheduler)
        val repository = repository(io)
        runCurrent()

        val result = whenTheProgramIsWritten(io) { repository.deleteProgram() }
        repository.refresh()
        runCurrent()

        assertNull(folder.onWrite)
        assertEquals(ProgramActionResult.Success, result())
        assertNull(repository.state.value.program)
        assertEquals(ProgramStatus.Idle, repository.state.value.status)
        assertEquals(DownloadPermission.ALLOWED, repository.state.value.permission)
        assertFalse(cacheFile.exists())
        assertEquals("the delete doesn't wait for the old program to be written again", 1, folder.writes)
    }

    @Test
    fun aDownloadThatFinishesFirstKeepsItsProgram() = runTest {
        // The other order: the program is written and shown, then downloads are switched off.
        cachedProgram(TestPages.NOW.minus(Duration.ofHours(7)))
        val io = StandardTestDispatcher(testScheduler)
        val repository = repository(io)
        repository.refresh()
        val downloaded = repository.state.value.program
        assertEquals(TestPages.NOW, downloaded?.fetchedAt)

        assertEquals(ProgramActionResult.Success, repository.setDownloadsEnabled(false))
        assertEquals(downloaded, repository.state.value.program)
        assertEquals(downloaded, ProgramCache(cacheFolder).read())
    }
}
