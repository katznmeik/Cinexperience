package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.model.DataLimits.MAX_HALLS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The total cap of [MAX_HALLS] halls in a backup: more are refused before
 * anything is touched, exactly as many export and import; and data beyond the cap
 * (only an older build could save it) can't be exported.
 */
internal class BackupHallCapTest : BackupTestBase() {

    // Exports and imports are compared row by row: no seeding in between.
    override val seedAfterImport: Boolean = false

    private val expected = "it has more than 1,000 halls."

    private fun data(hallCount: Int) = BackupData(
        backupVersion = BackupFormat.BACKUP_VERSION,
        cinemas = listOf(BackupCinema(1, "Kino", null, null, isPreloaded = false, seedKey = null, isFavourite = false)),
        halls = List(hallCount) { i ->
            BackupHall(
                id = i + 1L, cinemaId = 1, name = "Saal ${i + 1}", layoutJson = null, screenWidth = null, screenDistance = null,
                referenceImagePath = null, notes = null, isPreloaded = false, seedKey = null, nextSeatId = 1, screenOffset = 0f,
            )
        },
        visits = emptyList(),
        tickets = emptyList(),
        seatOverrides = emptyList(),
        seedSnapshots = emptyList(),
        metadata = emptyList(),
    )

    private fun problem(data: BackupData): String {
        try {
            BackupValidator.validate(data, emptySet())
        } catch (p: ValidationProblem) {
            return p.detail
        }
        fail("Expected the data to be refused")
        error("unreachable")
    }

    @Test
    fun theValidatorAcceptsExactlyTheCapAndRefusesOneMore() {
        BackupValidator.validate(data(MAX_HALLS), emptySet())
        assertEquals(expected, problem(data(MAX_HALLS + 1)))
    }

    /** [count] user halls in one new cinema, written straight into the tables (as an older build could have). */
    private suspend fun insertHalls(count: Int) {
        val cinemaId = cinemas.createCinema("Kino")
        repeat(count) { i ->
            db.hallDao().insert(
                HallEntity(
                    cinemaId = cinemaId, name = "Saal ${i + 1}", layoutJson = null, screenWidth = null, screenDistance = null,
                    referenceImagePath = null, notes = null, isPreloaded = false, seedKey = null,
                ),
            )
        }
    }

    @Test
    fun exactlyTheCapExportsAndImports() = runTest {
        insertHalls(MAX_HALLS)
        val doc = deviceA.document("cap.zip")
        assertEquals(MAX_HALLS, deviceA.repository.export(doc).halls)

        val deviceB = newDevice("b")
        File(doc).copyTo(File(deviceB.document("cap.zip")))
        val preview = deviceB.repository.prepareImport(deviceB.document("cap.zip"))
        deviceB.repository.commitImport(preview)
        assertEquals(MAX_HALLS, deviceB.db.hallDao().getAll().size)
    }

    @Test
    fun aBackupWithMoreHallsIsRefusedBeforeAnythingIsTouched() = runTest {
        insertHalls(2)
        val valid = File(deviceA.document("valid.zip"))
        deviceA.repository.export(valid.path)
        val before = deviceA.snapshot()

        val entries = readZip(valid)
        val i = entries.indexOfFirst { it.first == BackupFormat.DATA }
        val tooMany = data(MAX_HALLS + 1)
        entries[i] = BackupFormat.DATA to BackupFormat.writeJson.encodeToString(BackupData.serializer(), tooMany).toByteArray()
        // The manifest's counts must match, or the backup is refused for that instead.
        val m = entries.indexOfFirst { it.first == BackupFormat.MANIFEST }
        val manifest = BackupFormat.strictJson.decodeFromString(BackupManifest.serializer(), entries[m].second.decodeToString())
        val counts = tooMany.counts().toJson()
        entries[m] = BackupFormat.MANIFEST to
            BackupFormat.writeJson.encodeToString(BackupManifest.serializer(), manifest.copy(counts = counts)).toByteArray()
        val crafted = File(deviceA.root, "too-many.zip").apply { writeBytes(zipBytes(entries)) }

        val e = try {
            deviceA.repository.prepareImport(crafted.path)
            fail("Expected the backup to be refused")
            error("unreachable")
        } catch (e: BackupException) {
            e
        }
        assertEquals(e.message, BackupError.INVALID_DATA, e.error)
        assertEquals("The backup is damaged: $expected", e.message)
        assertEquals(before, deviceA.snapshot())
        assertTrue(deviceA.stagingIsEmpty())
    }

    @Test
    fun dataBeyondTheCapCanNotBeExported() = runTest {
        insertHalls(MAX_HALLS + 1)
        val doc = deviceA.document("too-many.zip")
        val e = try {
            deviceA.repository.export(doc)
            fail("Expected the export to be refused")
            error("unreachable")
        } catch (e: BackupException) {
            e
        }
        assertEquals(BackupError.INVALID_DATA, e.error)
        assertEquals("Your data can't be backed up: $expected", e.message)
        assertTrue(!File(doc).exists())
    }
}
