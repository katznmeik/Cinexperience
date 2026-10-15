package io.github.katznmeik.cinexperience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.program.DefaultProgramRepository
import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.ProgramFiles
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

/**
 * The real program repository as the app creates it (spec §11.1): its cache in
 * `files/program/`, untouched by the startup sweep, and its permission in shared
 * preferences. These tests never call `refresh`: nothing here touches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProgramStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = File(context.filesDir, ProgramFiles.FOLDER)
    private val cacheFile = File(folder, ProgramFiles.CACHE_FILE)

    private fun repository(scope: CoroutineScope): ProgramRepository =
        DefaultProgramRepository.create(context, "1.1.0", scope, ioDispatcher = Dispatchers.Unconfined)

    /** A cache file in the app's own format (version 1), as a download writes it. */
    private fun writeCache() {
        folder.mkdirs()
        cacheFile.writeText(
            """
            {"formatVersion":1,"fetchedAt":"2026-10-15T08:00:00Z",
             "films":[{"slug":"an-invented-film","kind":"FILM","title":"An Invented Film","tagline":null,"genre":null,
                       "runtimeMinutes":100,"fsk":12,"releaseDate":null}],
             "showings":[{"id":"1001-1","filmSlug":"an-invented-film","start":"2026-10-16T17:00","cinema":"Odeon",
                          "versions":["OV"],"eventLabels":[]}]}
            """.trimIndent(),
        )
    }

    @Test
    fun theCacheIsReadFromFilesProgramAndDeletedThere() = runTest {
        writeCache()
        val repository = repository(backgroundScope)
        val state = repository.state.first { it.isLoaded }
        val program = assertNotNullAndGet(state.program)
        assertEquals(Instant.parse("2026-10-15T08:00:00Z"), program.fetchedAt)
        assertEquals(LocalDateTime.of(2026, 10, 16, 17, 0), program.showings.single().start)
        assertEquals("odeon", program.showings.single().cinema.seedKey)
        assertEquals(DownloadPermission.NOT_ASKED, state.permission) // default

        repository.deleteProgram()
        assertFalse(cacheFile.exists())
        assertEquals(null, repository.state.value.program)
    }

    @Test
    fun thePermissionIsKeptInSharedPreferences() = runTest {
        repository(backgroundScope).allowDownloads()
        val stored = context.getSharedPreferences("program", Context.MODE_PRIVATE)
        assertEquals("allowed", stored.getString("download_permission", null))
        assertEquals(DownloadPermission.ALLOWED, repository(backgroundScope).state.first { it.isLoaded }.permission)

        repository(backgroundScope).setDownloadsEnabled(false)
        assertEquals(DownloadPermission.OFF, repository(backgroundScope).state.first { it.isLoaded }.permission)
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

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
