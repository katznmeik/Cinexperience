package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Every rejected backup leaves the current data, files and safety copies untouched. */
internal class BackupValidationTest : BackupTestBase() {

    private lateinit var valid: File

    private suspend fun exportValid() {
        buildRealisticData()
        valid = File(deviceA.document("valid.zip"))
        deviceA.repository.export(valid.path)
    }

    private fun write(name: String, bytes: ByteArray): String = File(deviceA.root, name).apply { writeBytes(bytes) }.path

    private fun modified(name: String, change: MutableList<Pair<String, ByteArray>>.() -> Unit): String {
        val entries = readZip(valid)
        entries.change()
        return write(name, zipBytes(entries))
    }

    private fun MutableList<Pair<String, ByteArray>>.editData(transform: (BackupData) -> BackupData) {
        val i = indexOfFirst { it.first == BackupFormat.DATA }
        val data = BackupFormat.strictJson.decodeFromString(BackupData.serializer(), this[i].second.decodeToString())
        this[i] = BackupFormat.DATA to BackupFormat.writeJson.encodeToString(BackupData.serializer(), transform(data)).toByteArray()
    }

    private fun MutableList<Pair<String, ByteArray>>.editJson(entry: String, transform: (JsonObject) -> JsonObject) {
        val i = indexOfFirst { it.first == entry }
        val json = BackupFormat.manifestJson.parseToJsonElement(this[i].second.decodeToString()).jsonObject
        this[i] = entry to transform(json).toString().toByteArray()
    }

    private suspend fun assertRejected(path: String, vararg errors: BackupError, device: Device = deviceA): BackupException {
        val before = device.snapshot()
        val filesBefore = device.files()
        val copiesBefore = device.safetyCopyFiles()
        val exception = try {
            device.repository.prepareImport(path)
            fail("Expected the backup to be rejected")
            error("unreachable")
        } catch (e: BackupException) {
            e
        }
        assertTrue("Got ${exception.error}: ${exception.message}", exception.error in errors)
        assertEquals(before, device.snapshot())
        assertEquals(filesBefore, device.files())
        assertEquals(copiesBefore, device.safetyCopyFiles())
        assertTrue(device.stagingIsEmpty())
        return exception
    }

    @Test
    fun corruptOrForeignFilesAreNotBackups() = runTest {
        exportValid()
        assertRejected(write("random.zip", Random.nextBytes(5000)), BackupError.NOT_A_BACKUP)
        assertRejected(write("text.zip", "hello".toByteArray()), BackupError.NOT_A_BACKUP)
        assertRejected(write("empty.zip", ByteArray(0)), BackupError.NOT_A_BACKUP)
        assertRejected(write("other.zip", zipBytes(listOf("readme.txt" to "hi".toByteArray()))), BackupError.UNSAFE_ARCHIVE)
        assertRejected(modified("no-manifest.zip") { removeAll { it.first == BackupFormat.MANIFEST } }, BackupError.NOT_A_BACKUP)
        assertRejected(
            modified("wrong-format.zip") { editJson(BackupFormat.MANIFEST) { JsonObject(it + ("format" to JsonPrimitive("something"))) } },
            BackupError.NOT_A_BACKUP,
        )
        assertRejected(modified("no-data.zip") { removeAll { it.first == BackupFormat.DATA } }, BackupError.INVALID_DATA)
    }

    @Test
    fun truncatedBackupIsRejected() = runTest {
        exportValid()
        val bytes = valid.readBytes()
        for (fraction in listOf(0.1, 0.5, 0.9)) {
            val truncated = bytes.copyOf((bytes.size * fraction).toInt())
            assertRejected(write("truncated-$fraction.zip", truncated), BackupError.NOT_A_BACKUP, BackupError.MISSING_FILE)
        }
    }

    @Test
    fun newerBackupVersionAsksForAnUpdate() = runTest {
        exportValid()
        val newer = modified("newer.zip") {
            editJson(BackupFormat.MANIFEST) { JsonObject(it + ("backupVersion" to JsonPrimitive(2)) + ("newField" to JsonPrimitive(true))) }
        }
        val e = assertRejected(newer, BackupError.NEWER_VERSION)
        assertTrue(e.message!!.contains("newer version"))
    }

    @Test
    fun unsafeEntryNamesAreRejected() = runTest {
        exportValid()
        val names = listOf(
            "../evil.jpg",
            "/abs.jpg",
            "photos/../../evil.jpg",
            "photos/sub/x.jpg",
            "photos\\x.jpg",
            "photos/.hidden",
            "photos/",
            "other/x.jpg",
            "data.json.bak",
            "evil/",
        ).filter { it != "photos/" } // the bare folder entry is allowed
        for ((i, name) in names.withIndex()) {
            assertRejected(modified("unsafe-$i.zip") { add(name to byteArrayOf(1, 2, 3)) }, BackupError.UNSAFE_ARCHIVE)
        }
        assertFalse(File(deviceA.root, "evil.jpg").exists())
        assertFalse(File(deviceA.cacheDir, "evil.jpg").exists())
    }

    @Test
    fun folderEntriesAreAccepted() = runTest {
        exportValid()
        val withFolders = modified("folders.zip") { add(2, "photos/" to ByteArray(0)); add(2, "reference/" to ByteArray(0)) }
        val preview = deviceA.repository.prepareImport(withFolders)
        deviceA.repository.discardImport(preview)
        assertTrue(deviceA.stagingIsEmpty())
    }

    @Test
    fun duplicateEntryNamesAreRejected() = runTest {
        exportValid()
        val entries = readZip(valid)
        val photo = entries.first { it.first == "photos/visit-1.jpg" }
        entries += "photos/visit-9.jpg" to Random.nextBytes(100)
        // ZipOutputStream refuses duplicates, so rename the extra entry in the bytes.
        val bytes = zipBytes(entries)
        val duplicated = replaceAll(bytes, "photos/visit-9.jpg".toByteArray(), photo.first.toByteArray())
        assertRejected(write("duplicate.zip", duplicated), BackupError.UNSAFE_ARCHIVE)
        val duplicatedManifest = replaceAll(
            zipBytes(readZip(valid) + ("manifest.jsoX" to "{}".toByteArray())),
            "manifest.jsoX".toByteArray(),
            "manifest.json".toByteArray(),
        )
        assertRejected(write("duplicate-manifest.zip", duplicatedManifest), BackupError.UNSAFE_ARCHIVE)
    }

    private fun replaceAll(bytes: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        val result = bytes.copyOf()
        var i = 0
        while (i <= result.size - from.size) {
            if ((from.indices).all { result[i + it] == from[it] }) {
                to.copyInto(result, i)
                i += from.size
            } else {
                i++
            }
        }
        return result
    }

    @Test
    fun oversizedEntriesAndTooManyEntriesAreRejected() = runTest {
        exportValid()
        val small = newDevice("small", BackupLimits(maxFileBytes = 10_000))
        val bomb = modified("bomb.zip") {
            val i = indexOfFirst { it.first == "photos/visit-1.jpg" }
            this[i] = "photos/visit-1.jpg" to ByteArray(1_000_000) // compresses to about 1 KB
        }
        assertTrue(File(bomb).length() < 100_000)
        assertRejected(bomb, BackupError.TOO_LARGE, device = small)

        assertRejected(valid.path, BackupError.TOO_LARGE, device = newDevice("total", BackupLimits(maxTotalBytes = 5_000)))
        assertRejected(valid.path, BackupError.TOO_LARGE, device = newDevice("count", BackupLimits(maxEntries = 3)))
        assertRejected(valid.path, BackupError.TOO_LARGE, device = newDevice("data", BackupLimits(maxDataBytes = 1_000)))
    }

    @Test
    fun aRealZipBombHitsTheDefaultLimit() = runTest {
        exportValid()
        val bomb = File(deviceA.root, "real-bomb.zip")
        ZipOutputStream(bomb.outputStream()).use { zip ->
            for ((name, content) in readZip(valid).filter { it.first != "photos/visit-1.jpg" }) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("photos/visit-1.jpg"))
            val zeros = ByteArray(1 shl 20)
            repeat(100) { zip.write(zeros) } // 100 MB of zeros
            zip.closeEntry()
        }
        assertTrue(bomb.length() < 1_000_000)
        assertRejected(bomb.path, BackupError.TOO_LARGE)
    }

    @Test
    fun missingReferencedFileIsRejected() = runTest {
        exportValid()
        assertRejected(modified("no-photo.zip") { removeAll { it.first == "photos/visit-1.jpg" } }, BackupError.MISSING_FILE)
        assertRejected(modified("no-reference.zip") { removeAll { it.first.startsWith("reference/") } }, BackupError.MISSING_FILE)
    }

    @Test
    fun brokenReferencesAreRejected() = runTest {
        exportValid()
        val cases = mapOf<String, (BackupData) -> BackupData>(
            "visit-hall" to { d -> d.copy(visits = d.visits.mapIndexed { i, v -> if (i == 0) v.copy(hallId = 999_999) else v }) },
            "hall-cinema" to { d -> d.copy(halls = d.halls.mapIndexed { i, h -> if (i == 0) h.copy(cinemaId = 999_999) else h }) },
            "override-hall" to { d -> d.copy(seatOverrides = d.seatOverrides.mapIndexed { i, o -> if (i == 0) o.copy(hallId = 999_999) else o }) },
            "override-seat" to { d -> d.copy(seatOverrides = d.seatOverrides.mapIndexed { i, o -> if (i == 0) o.copy(seatId = 99_999) else o }) },
            "layout" to { d -> d.copy(halls = d.halls.map { if (it.layoutJson != null) it.copy(layoutJson = "{\"formatVersion\": 1, \"rows\": [") else it }) },
            "newer-layout" to { d -> d.copy(halls = d.halls.map { h -> h.copy(layoutJson = h.layoutJson?.replace("\"formatVersion\":", "\"formatVersion\":9,\"x\":")) }) },
            "duplicate-visit" to { d -> d.copy(visits = d.visits + d.visits.first().copy(photoPath = null)) },
            "duplicate-cinema-key" to { d -> d.copy(cinemas = d.cinemas.mapIndexed { i, c -> if (i == 1) c.copy(seedKey = d.cinemas[0].seedKey) else c }) },
            "rating" to { d -> d.copy(visits = d.visits.mapIndexed { i, v -> if (i == 0) v.copy(seatRating = 7) else v }) },
            "date" to { d -> d.copy(visits = d.visits.mapIndexed { i, v -> if (i == 0) v.copy(date = "2026-13-45") else v }) },
            "format" to { d -> d.copy(visits = d.visits.mapIndexed { i, v -> if (i == 0) v.copy(screeningFormat = "IMAX") else v }) },
            "two-favourites" to { d ->
                val o = d.seatOverrides.first { it.isFavourite }
                val other = d.seatOverrides.first { it.hallId == o.hallId && !it.isFavourite }
                d.copy(seatOverrides = d.seatOverrides.map { if (it == other) it.copy(isFavourite = true) else it })
            },
            "blank-name" to { d -> d.copy(cinemas = d.cinemas.mapIndexed { i, c -> if (i == 0) c.copy(name = " ") else c }) },
            "mark" to { d -> d.copy(halls = d.halls.mapIndexed { i, h -> if (i == 0) h.copy(nextSeatId = 0) else h }) },
        )
        for ((name, change) in cases) {
            assertRejected(modified("$name.zip") { editData(change) }, BackupError.INVALID_DATA)
        }
    }

    @Test
    fun unsafeFilePathsInTheDataAreRejected() = runTest {
        exportValid()
        val paths = listOf("photos/../../databases/x.db", "/data/photo.jpg", "reference/visit-1.jpg", "photos/a/b.jpg")
        for ((i, path) in paths.withIndex()) {
            val zip = modified("path-$i.zip") {
                add(path.removePrefix("/") to byteArrayOf(1))
                editData { d -> d.copy(visits = d.visits.map { if (it.photoPath == "photos/visit-1.jpg") it.copy(photoPath = path) else it }) }
            }
            assertRejected(zip, BackupError.INVALID_DATA, BackupError.UNSAFE_ARCHIVE)
        }
    }

    @Test
    fun unknownOrMissingFieldsAndCountMismatchesAreRejected() = runTest {
        exportValid()
        assertRejected(
            modified("unknown-field.zip") { editJson(BackupFormat.DATA) { JsonObject(it + ("extra" to JsonPrimitive(1))) } },
            BackupError.INVALID_DATA,
        )
        assertRejected(
            modified("missing-field.zip") { editJson(BackupFormat.DATA) { JsonObject(it - "seedSnapshots") } },
            BackupError.INVALID_DATA,
        )
        assertRejected(
            modified("version-mismatch.zip") { editJson(BackupFormat.DATA) { JsonObject(it + ("backupVersion" to JsonPrimitive(0))) } },
            BackupError.INVALID_DATA,
        )
        assertRejected(
            modified("counts.zip") {
                editJson(BackupFormat.MANIFEST) { m ->
                    val counts = m.getValue("counts").jsonObject
                    JsonObject(m + ("counts" to JsonObject(counts + ("visits" to JsonPrimitive(99)))))
                }
            },
            BackupError.INVALID_DATA,
        )
        assertRejected(
            modified("bad-date.zip") { editJson(BackupFormat.MANIFEST) { JsonObject(it + ("exportedAt" to JsonPrimitive("yesterday"))) } },
            BackupError.INVALID_DATA,
        )
    }

    @Test
    fun deeplyNestedJsonIsRejectedInsteadOfCrashing() = runTest {
        exportValid()
        val deep = "[".repeat(200_000)
        fun replaced(name: String, entry: String, content: String) = modified(name) {
            val i = indexOfFirst { it.first == entry }
            this[i] = entry to content.toByteArray()
        }
        assertRejected(replaced("deep-data.zip", BackupFormat.DATA, deep), BackupError.INVALID_DATA)
        assertRejected(
            replaced("deep-field.zip", BackupFormat.DATA, """{"backupVersion":1,"cinemas":$deep"""),
            BackupError.INVALID_DATA,
        )
        assertRejected(replaced("deep-manifest.zip", BackupFormat.MANIFEST, "[".repeat(60_000)), BackupError.NOT_A_BACKUP)
        assertRejected(
            replaced("deep-manifest-field.zip", BackupFormat.MANIFEST, """{"format":"cinexperience-backup","x":${"[".repeat(60_000)}"""),
            BackupError.NOT_A_BACKUP,
        )
    }

    @Test
    fun filesBeforeTheDataAreRejected() = runTest {
        exportValid()
        val reordered = modified("reordered.zip") {
            val data = first { it.first == BackupFormat.DATA }
            remove(data)
            add(data) // data.json last, after the photos
        }
        val e = assertRejected(reordered, BackupError.INVALID_DATA)
        assertTrue(e.message!!.contains("data.json must come before"))
        val noManifestFirst = modified("manifest-last.zip") {
            val manifest = first { it.first == BackupFormat.MANIFEST }
            remove(manifest)
            add(manifest)
        }
        assertRejected(noManifestFirst, BackupError.NOT_A_BACKUP)
    }

    @Test
    fun unreferencedEntriesAreSkippedButStillCapped() = runTest {
        exportValid()
        val extra = modified("extra.zip") { add("photos/visit-unreferenced.jpg" to Random.nextBytes(500)) }
        val deviceB = newDevice("b")
        val preview = deviceB.repository.prepareImport(extra)
        val staged = File(deviceB.cacheDir, RoomBackupRepository.STAGING_FOLDER).walk().filter { it.isFile }.map { it.name }.toList()
        assertFalse(staged.contains("visit-unreferenced.jpg"))
        deviceB.repository.commitImport(preview)
        assertFalse(File(deviceB.filesDir, "photos/visit-unreferenced.jpg").exists())

        val bomb = modified("unreferenced-bomb.zip") { add("photos/visit-bomb.jpg" to ByteArray(1_000_000)) }
        assertRejected(bomb, BackupError.TOO_LARGE, device = newDevice("small", BackupLimits(maxFileBytes = 10_000)))
    }

    @Test
    fun aRejectedBackupDoesNotBlockTheNextImport() = runTest {
        exportValid()
        assertRejected(write("random.zip", Random.nextBytes(100)), BackupError.NOT_A_BACKUP)
        val preview = deviceA.repository.prepareImport(valid.path)
        assertEquals(deviceA.snapshot().counts(), preview.counts)
    }

    @Test
    fun anUnopenableFileIsAReadError() = runTest {
        assertRejected(File(deviceA.root, "does-not-exist.zip").path, BackupError.READ_FAILED)
    }
}
