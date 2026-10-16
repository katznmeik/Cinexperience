package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.db.MetadataEntity
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Seeding after an import follows the backup's own seed state: an item the
 * backup's user deleted (its seed key has seed records) stays deleted, and an
 * item the backup has neither as a row nor in its seed records is added.
 */
internal class BackupSeedDeletedItemsTest : BackupTestBase() {

    /** Makes the next seeding on device A's database apply the bundled seed again. */
    private suspend fun olderSeedVersion() {
        db.metadataDao().put(MetadataEntity(Seeder.KEY_SEED_VERSION, "1"))
    }

    private suspend fun importInto(device: Device, document: String) {
        device.repository.commitImport(device.repository.prepareImport(document))
    }

    @Test
    fun aCinemaTheBackupsUserDeletedStaysDeletedAfterTheImport() = runTest {
        // "Odeon" typed in by hand before the seed had it, then adopted, then deleted.
        val odeon = cinemas.createCinema("Odeon")
        deviceA.seed()
        val odeonHall = db.hallDao().getAll().single { it.cinemaId == odeon }
        assertFalse(odeonHall.isPreloaded)
        assertEquals(DeleteResult.DELETED, cinemas.deleteCinema(odeon))
        olderSeedVersion()
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)

        val deviceB = newDevice("b")
        deviceB.seed()
        importInto(deviceB, doc)

        assertNull(deviceB.db.cinemaDao().getAll().firstOrNull { it.seedKey == "odeon" })
        assertNull(deviceB.db.hallDao().getAll().firstOrNull { it.seedKey == odeonHall.seedKey })
        assertEquals(13, deviceB.db.cinemaDao().getAll().size)
    }

    @Test
    fun aCinemaMissingWithItsSeedRecordsIsAddedAfterTheImport() = runTest {
        // A backup made before the seed had "yorck": neither the rows nor the seed records.
        deviceA.seed()
        val yorck = db.cinemaDao().getAll().single { it.seedKey == "yorck" }
        val yorckHalls = db.hallDao().getAll().filter { it.cinemaId == yorck.id }.mapNotNull { it.seedKey }.toSet()
        db.cinemaDao().delete(yorck.id) // cascades over its halls
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM seed_snapshot WHERE (kind = 'cinema' AND seed_key = 'yorck') OR (kind = 'hall' AND seed_key LIKE 'yorck/%')",
        )
        olderSeedVersion()
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)

        val deviceB = newDevice("b")
        deviceB.seed()
        importInto(deviceB, doc)

        val added = deviceB.db.cinemaDao().getAll().single { it.seedKey == "yorck" }
        assertTrue(added.isPreloaded)
        val addedHalls = deviceB.db.hallDao().getAll().filter { it.cinemaId == added.id }
        assertEquals(yorckHalls, addedHalls.mapNotNull { it.seedKey }.toSet())
        assertTrue(addedHalls.all { it.isPreloaded })
        assertEquals(14, deviceB.db.cinemaDao().getAll().size)
        assertEquals(38, deviceB.db.hallDao().getAll().size)
    }

    @Test
    fun aVersion1BackupGetsEveryMissingSeedItem() = runTest {
        // The v1 fixture holds one seed cinema with one hall and a single seed record.
        val fixture = File("src/test/resources/backup/v1")
        val zip = File(deviceA.root, "v1.zip").apply {
            writeBytes(
                zipBytes(
                    listOf(
                        BackupFormat.MANIFEST to File(fixture, "manifest.json").readBytes(),
                        BackupFormat.DATA to File(fixture, "data.json").readBytes(),
                        "photos/visit-1.jpg" to ByteArray(2000) { it.toByte() },
                    ),
                ),
            )
        }
        deviceA.seed()
        importInto(deviceA, zip.path)

        val all = db.cinemaDao().getAll()
        assertEquals(15, all.size) // the 14 seed cinemas and the user's "Kiezkino"
        assertEquals(39, db.hallDao().getAll().size) // the 38 seed halls and the user's "Saal A"
        assertEquals("Saal Eins", db.hallDao().getAll().single { it.seedKey == "delphi-lux/saal-1" }.name)
        assertTrue(all.filter { it.seedKey != null }.all { it.isPreloaded })
    }
}
