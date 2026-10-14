package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

class BackupFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun filePathsMustBeSafeNamesInsideTheirFolder() {
        assertTrue(BackupFormat.isFilePath("photos/visit-0b1c2d3e-aaaa-bbbb-cccc-000000000000.jpg"))
        assertTrue(BackupFormat.isFilePath("reference/hall-12-x.jpg", "reference"))
        assertFalse(BackupFormat.isFilePath("reference/hall-12-x.jpg", "photos"))
        for (bad in listOf("", "photos/", "/photos/a.jpg", "photos/../a.jpg", "photos/a/b.jpg", "photos\\a.jpg", "photos/.a", "a.jpg", "other/a.jpg", "photos/a b.jpg", "photos/" + "a".repeat(200))) {
            assertFalse(bad, BackupFormat.isFilePath(bad))
        }
    }

    @Test
    fun migrationsOnlyAcceptKnownVersions() {
        val data = JsonObject(mapOf("backupVersion" to JsonPrimitive(1)))
        assertEquals(data, BackupMigrations.migrate(data, 1))
        assertThrows(IllegalArgumentException::class.java) { BackupMigrations.migrate(data, 0) }
        assertThrows(IllegalArgumentException::class.java) { BackupMigrations.migrate(data, BackupFormat.BACKUP_VERSION + 1) }
    }

    @Test
    fun suggestedFileNameHasTheDate() {
        assertEquals("cinexperience-backup-2026-10-03.zip", BackupRepository.suggestedFileName(LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun safetyCopiesAreNamedByTimeAndPrunedToTheNewest() {
        val dir = tmp.newFolder("backups")
        val copies = SafetyCopies(dir)
        assertNull(copies.latest())
        val times = listOf("2026-10-01T10:00:00Z", "2026-10-03T09:00:00Z", "2026-10-02T08:00:00Z").map(Instant::parse)
        for (time in times) copies.fileFor(time).writeText("x")
        File(dir, "pre-import-x.zip.tmp").writeText("leftover")
        File(dir, "unrelated.txt").writeText("keep")
        assertEquals(Instant.parse("2026-10-03T09:00:00Z"), copies.latest()!!.createdAt)
        assertEquals("pre-import-20261003T090000000Z.zip", copies.latest()!!.file.name)

        copies.prune()
        assertEquals(
            listOf("pre-import-20261002T080000000Z.zip", "pre-import-20261003T090000000Z.zip", "unrelated.txt"),
            dir.list()!!.sorted(),
        )
    }

    @Test
    fun aSafetyCopyNeverOverwritesAnother() {
        val copies = SafetyCopies(tmp.newFolder("backups"))
        val at = Instant.parse("2026-10-03T09:00:00Z")
        val first = copies.fileFor(at).apply { writeText("first") }
        val second = copies.fileFor(at)
        assertTrue(first.name != second.name)
    }
}
