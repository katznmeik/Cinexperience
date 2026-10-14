package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val data = JsonObject(mapOf("backupVersion" to JsonPrimitive(2)))
        assertEquals(data, BackupMigrations.migrate(data, 2))
        assertThrows(IllegalArgumentException::class.java) { BackupMigrations.migrate(data, 0) }
        assertThrows(IllegalArgumentException::class.java) { BackupMigrations.migrate(data, BackupFormat.BACKUP_VERSION + 1) }
    }

    @Test
    fun version1To2AddsTheDefaultsToEveryCinemaAndHall() {
        val v1 = Json.parseToJsonElement(
            """
            {"backupVersion": 1,
             "cinemas": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}],
             "halls": [{"id": 3, "name": "Saal 1"}],
             "visits": [{"id": 4}]}
            """,
        ).jsonObject
        val v2 = BackupMigrations.migrate(v1, 1)
        assertEquals(JsonPrimitive(2), v2["backupVersion"])
        assertEquals(
            listOf(JsonPrimitive(false), JsonPrimitive(false)),
            v2.getValue("cinemas").jsonArray.map { it.jsonObject["isFavourite"] },
        )
        assertEquals(0.0, v2.getValue("halls").jsonArray.single().jsonObject.getValue("screenOffset").jsonPrimitive.double, 0.0)
        // Everything else is left as it was.
        assertEquals(v1.getValue("visits"), v2.getValue("visits"))
        assertEquals(JsonPrimitive("Saal 1"), v2.getValue("halls").jsonArray.single().jsonObject["name"])
        // No lists, or rows that aren't objects: left for the strict decoder to reject.
        assertEquals(JsonPrimitive(2), BackupMigrations.migrate(JsonObject(mapOf("backupVersion" to JsonPrimitive(1))), 1)["backupVersion"])
        val odd = Json.parseToJsonElement("""{"backupVersion": 1, "cinemas": [7], "halls": "x"}""").jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(7))), BackupMigrations.migrate(odd, 1)["cinemas"])
        // A version 1 row with a version 2 field is invalid.
        val withField = Json.parseToJsonElement("""{"backupVersion": 1, "halls": [{"id": 3, "screenOffset": 1.5}]}""").jsonObject
        assertThrows(IllegalArgumentException::class.java) { BackupMigrations.migrate(withField, 1) }
    }

    @Test
    fun suggestedFileNameHasTheDate() {
        assertEquals("cinexperience-backup-2026-10-03.zip", BackupRepository.suggestedFileName(LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun safetyCopiesAreOrderedBySequenceNotByTheClock() {
        val dir = tmp.newFolder("backups")
        val copies = SafetyCopies(dir)
        assertNull(copies.latest())
        // The clock jumps around; the order of writing decides.
        val times = listOf("2026-10-01T10:00:00Z", "2026-10-03T09:00:00Z", "2026-10-02T08:00:00Z").map(Instant::parse)
        for (time in times) copies.newFile(time).writeText("x")
        File(dir, "pre-import-9-x.zip.tmp").writeText("leftover")
        File(dir, "unrelated.txt").writeText("keep")
        assertEquals(3L, copies.latest()!!.sequence)
        assertEquals(Instant.parse("2026-10-02T08:00:00Z"), copies.latest()!!.createdAt)
        assertEquals("pre-import-3-20261002T080000000Z.zip", copies.latest()!!.file.name)

        copies.prune()
        assertEquals(
            listOf("pre-import-2-20261003T090000000Z.zip", "pre-import-3-20261002T080000000Z.zip", "unrelated.txt"),
            dir.list()!!.sorted(),
        )
    }

    @Test
    fun aSafetyCopyNeverOverwritesAnother() {
        val copies = SafetyCopies(tmp.newFolder("backups"))
        val at = Instant.parse("2026-10-03T09:00:00Z")
        val first = copies.newFile(at).apply { writeText("first") }
        val second = copies.newFile(at)
        assertTrue(first.name != second.name)
        second.writeText("second")
        // Sequence numbers compare as numbers, not text.
        repeat(9) { copies.newFile(at).writeText("x") }
        assertEquals(11L, copies.latest()!!.sequence)
    }
}
