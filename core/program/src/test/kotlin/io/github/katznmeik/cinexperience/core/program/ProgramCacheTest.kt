package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ProgramCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val filesDir: File by lazy { temp.newFolder("files") }
    private val folder: File by lazy { File(filesDir, ProgramFiles.FOLDER) }
    private val cache: ProgramCache by lazy { ProgramCache(folder) }
    private val file: File get() = File(folder, ProgramFiles.CACHE_FILE)

    private fun program(fetchedAt: String = "2026-10-15T08:00:00Z", title: String = "The Quiet Hour") = Program(
        fetchedAt = Instant.parse(fetchedAt),
        films = listOf(
            Film("the-quiet-hour", title, FilmKind.FILM, "A tagline.", "Drama", 104, 12, LocalDate.of(2026, 10, 1)),
            Film("night-of-reels", "Night of Reels", FilmKind.SPECIAL),
        ),
        showings = listOf(
            Showing(
                "1001-1", "the-quiet-hour", LocalDateTime.of(2026, 10, 15, 17, 0), ProgramCinema.fromYorckName("Kino International"),
                listOf(ShowingVersion.OMU, ShowingVersion.OMEU), listOf("Premiere", "Kids"),
            ),
            Showing("1001-2", "night-of-reels", LocalDateTime.of(2026, 10, 16, 22, 0), ProgramCinema.fromYorckName("Kino Unbekannt")),
        ),
    )

    @Test
    fun writesAndReadsTheProgram() {
        assertNull(cache.read())
        assertTrue(cache.write(program()))
        assertEquals(program(), cache.read())
        assertEquals(listOf(ProgramFiles.FOLDER), filesDir.list()!!.toList()) // nothing outside its folder
        assertEquals(listOf(ProgramFiles.CACHE_FILE), folder.list()!!.toList()) // no temporary files left
    }

    @Test
    fun aNewProgramReplacesTheOldOne() {
        assertTrue(cache.write(program(title = "Old")))
        assertTrue(cache.write(program(fetchedAt = "2026-10-15T14:00:00Z", title = "New")))
        val read = cache.read()!!
        assertEquals(Instant.parse("2026-10-15T14:00:00Z"), read.fetchedAt)
        assertEquals("New", read.films.first().title)
        assertEquals(listOf(ProgramFiles.CACHE_FILE), folder.list()!!.toList())
    }

    @Test
    fun aLeftoverTemporaryFileFromACrashDoesNotMatter() {
        assertTrue(cache.write(program(title = "Old")))
        // A crash during a later write leaves only a temporary file; the cache is still the old one.
        File(folder, "program-123.tmp").writeText("{\"formatVersion\":1,\"fetchedAt\":")
        assertEquals("Old", cache.read()!!.films.first().title)
        assertTrue(cache.write(program(title = "New")))
        assertEquals(listOf(ProgramFiles.CACHE_FILE), folder.list()!!.toList())
    }

    @Test
    fun aFailedWriteKeepsTheOldFileAndLeavesNoTemporaryFile() {
        assertTrue(cache.write(program(title = "Old")))
        val blocked = ProgramCache(File(filesDir, "blocked"))
        // The cache path is taken by a non-empty folder: the final rename fails.
        File(filesDir, "blocked/${ProgramFiles.CACHE_FILE}/x").apply { parentFile!!.mkdirs() }.writeText("x")
        assertFalse(blocked.write(program(title = "New")))
        assertEquals(listOf(ProgramFiles.CACHE_FILE), File(filesDir, "blocked").list()!!.toList())
        // Its folder can't be created: a file is in the way.
        File(filesDir, "file-in-the-way").writeText("x")
        assertFalse(ProgramCache(File(filesDir, "file-in-the-way")).write(program()))
        // The real cache is untouched.
        assertEquals("Old", cache.read()!!.films.first().title)
    }

    @Test
    fun aCorruptOrUnreadableCacheCountsAsNone() {
        folder.mkdirs()
        val bad = listOf(
            "",
            "not json",
            "{",
            "[]",
            "null",
            "{\"formatVersion\":1}",
            "{\"formatVersion\":2,\"fetchedAt\":\"2026-10-15T08:00:00Z\",\"films\":[],\"showings\":[]}",
            "{\"formatVersion\":1,\"fetchedAt\":\"yesterday\",\"films\":[],\"showings\":[]}",
            "{\"formatVersion\":1,\"fetchedAt\":\"2026-10-15T08:00:00Z\",\"films\":[],\"showings\":[]}",
            "{\"formatVersion\":1,\"fetchedAt\":\"2026-10-15T08:00:00Z\",\"films\":[],\"showings\":[],\"extra\":1}",
            "[".repeat(100_000),
        )
        for (content in bad) {
            file.writeText(content)
            assertNull(content.take(80), cache.read())
        }
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01))
        assertNull(cache.read())
        // Truncated valid file.
        assertTrue(cache.write(program()))
        file.writeText(file.readText().dropLast(20))
        assertNull(cache.read())
        // A folder where the file should be.
        file.delete()
        file.mkdirs()
        assertNull(cache.read())
    }

    @Test
    fun anOversizedCacheIsNotRead() {
        assertTrue(cache.write(program()))
        RandomAccessFile(file, "rw").use { it.setLength(ProgramCache.MAX_FILE_BYTES + 1) } // sparse
        assertNull(cache.read())
    }

    @Test
    fun storedValuesAreCheckedAgain() {
        assertTrue(cache.write(program()))
        val original = file.readText()

        // A film with a damaged slug is skipped, with its showings.
        file.writeText(original.replace("\"night-of-reels\"", "\"Night Of Reels\""))
        val withoutSpecial = cache.read()!!
        assertEquals(listOf("the-quiet-hour"), withoutSpecial.films.map { it.slug })
        assertEquals(listOf("1001-1"), withoutSpecial.showings.map { it.id })

        // A control character in a title, an invalid showing ID, an unknown kind.
        file.writeText(original.replace("\"The Quiet Hour\"", "\"The\\u0000Quiet Hour\"").replace("\"1001-2\"", "\"1001-2&x\""))
        assertNull(cache.read())
        file.writeText(original.replace("\"SPECIAL\"", "\"TRAILER\""))
        assertEquals(listOf("the-quiet-hour"), cache.read()!!.films.map { it.slug })

        // Unknown versions are dropped.
        file.writeText(original.replace("\"OmeU\"", "\"3D\""))
        assertEquals(listOf(ShowingVersion.OMU), cache.read()!!.showings.first().versions)

        // Only Yorck's cinema name is stored; the seed key is matched again when reading.
        assertFalse(original.contains("\"international\""))
        assertEquals(ProgramCinema("Kino International", "international"), cache.read()!!.showings.first().cinema)
    }

    @Test
    fun deleteRemovesTheCache() {
        assertTrue(cache.delete()) // nothing there
        assertTrue(cache.write(program()))
        File(folder, "program-9.tmp").writeText("x")
        assertTrue(cache.delete())
        assertNull(cache.read())
        assertEquals(emptyList<String>(), folder.list()!!.toList())
    }
}
