package io.github.katznmeik.cinexperience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.program.DefaultProgramRepository
import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.ProgramActionResult
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ProgramFiles
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.program.net.ProgramTransport
import io.github.katznmeik.cinexperience.core.program.net.TransportAbort
import io.github.katznmeik.cinexperience.core.program.net.TransportResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real program repository as the app creates it (spec §11.1): its cache in
 * `files/program/`, untouched by the startup sweep, and its permission in shared
 * preferences. Its transport is replaced by one that always fails, so no test
 * here can reach the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProgramStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = File(context.filesDir, ProgramFiles.FOLDER)
    private val cacheFile = File(folder, ProgramFiles.CACHE_FILE)

    /** Refuses every request, like a phone without network. */
    private class NoNetwork : ProgramTransport {
        val requests = AtomicInteger(0)

        override fun get(uri: URI, headers: Map<String, String>, abort: TransportAbort): TransportResponse {
            requests.incrementAndGet()
            throw IOException("Tests never reach the network")
        }
    }

    private val transport = NoNetwork()

    private fun repository(scope: CoroutineScope): ProgramRepository =
        DefaultProgramRepository.create(context, "1.1.0", scope, ioDispatcher = Dispatchers.Unconfined, transport = transport)

    /** A cache file in the app's own format (version 2), as a download writes it. */
    private fun writeCache() {
        folder.mkdirs()
        cacheFile.writeText(
            """
            {"formatVersion":2,"fetchedAt":"2026-10-15T08:00:00Z",
             "films":[{"kind":"FILM","slug":"an-invented-film","title":"An Invented Film","tagline":null,"genre":null,
                       "runtimeMinutes":100,"fsk":12,"releaseDate":null}],
             "showings":[{"id":"1001-1","film":{"kind":"FILM","slug":"an-invented-film"},"start":"2026-10-16T17:00",
                          "cinema":"Odeon","versions":["OV"],"eventLabels":[],"specials":[]}]}
            """.trimIndent(),
        )
    }

    @Test
    fun theCacheIsReadFromFilesProgramAndDeletedThere() = runTest {
        writeCache()
        val repository = repository(backgroundScope)
        val state = repository.state.first { it.isLoaded }
        val program = state.program
        assertNotNull(program)
        assertEquals(Instant.parse("2026-10-15T08:00:00Z"), program!!.fetchedAt)
        assertEquals(LocalDateTime.of(2026, 10, 16, 17, 0), program.showings.single().start)
        assertEquals("odeon", program.showings.single().cinema.seedKey)
        assertEquals(DownloadPermission.NOT_ASKED, state.permission) // default

        assertEquals(ProgramActionResult.Success, repository.deleteProgram())
        assertFalse(cacheFile.exists())
        assertNull(repository.state.value.program)
    }

    @Test
    fun thePermissionIsKeptInSharedPreferences() = runTest {
        assertEquals(ProgramActionResult.Success, repository(backgroundScope).allowDownloads())
        val stored = context.getSharedPreferences("program", Context.MODE_PRIVATE)
        assertEquals("allowed", stored.getString("download_permission", null))
        assertEquals(DownloadPermission.ALLOWED, repository(backgroundScope).state.first { it.isLoaded }.permission)

        assertEquals(ProgramActionResult.Success, repository(backgroundScope).setDownloadsEnabled(false))
        assertEquals(DownloadPermission.OFF, repository(backgroundScope).state.first { it.isLoaded }.permission)
    }

    @Test
    fun aDownloadUsesTheInjectedTransportOnly() = runTest {
        val repository = repository(backgroundScope)
        repository.allowDownloads()
        repository.refresh()
        assertEquals(1, transport.requests.get())
        assertEquals(ProgramStatus.Failed(ProgramError.NETWORK), repository.state.value.status)
    }

    @Test
    fun theStartupSweepNeverTouchesTheProgram() = runTest {
        writeCache()
        val leftover = File(folder, "program-1.tmp").apply { writeText("x") }
        val old = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        cacheFile.setLastModified(old)
        leftover.setLastModified(old)

        DataContainer(context, backgroundScope, Dispatchers.Default, Dispatchers.IO).sweepOrphanFiles()

        assertTrue(cacheFile.exists())
        assertTrue(leftover.exists())
    }
}
