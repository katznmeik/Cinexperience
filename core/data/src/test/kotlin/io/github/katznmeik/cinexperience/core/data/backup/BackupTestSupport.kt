package io.github.katznmeik.cinexperience.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.DatabaseConfig
import io.github.katznmeik.cinexperience.core.data.DatabaseTest
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.save
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A clock the test moves by hand. */
internal class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** [DocumentAccess] on plain files: the "URI" is a file path. */
internal class FileDocuments : DocumentAccess {
    val deleted = mutableListOf<String>()

    /** Wraps every output stream, e.g. to make writing fail. */
    var wrapOutput: (OutputStream) -> OutputStream = { it }

    override fun openInput(uri: String): InputStream = File(uri).inputStream()

    override fun openOutput(uri: String): OutputStream = wrapOutput(FileOutputStream(File(uri)))

    override fun delete(uri: String) {
        deleted += uri
        File(uri).delete()
    }
}

/** Fails once more than [limit] bytes were written. */
internal class FailingOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
    private var written = 0L

    override fun write(b: Int) {
        if (++written > limit) throw IOException("Disk full")
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }
}

/** The entries of a zip file, in order. */
internal fun readZip(file: File): MutableList<Pair<String, ByteArray>> {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(file.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries += entry.name to zip.readBytes()
        }
    }
    return entries
}

internal fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

/**
 * Base for backup tests: the [DatabaseTest] database is device "A"; [newDevice]
 * creates more devices, each with its own database, files, cache and documents.
 */
internal abstract class BackupTestBase : DatabaseTest() {

    @get:Rule
    val tmp = TemporaryFolder()

    protected val seedAsset: String = File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText()

    protected val clock = MutableClock(Instant.parse("2026-10-03T12:05:00Z"))

    private val extraDatabases = mutableListOf<CinexDatabase>()

    @After
    fun closeExtraDatabases() {
        extraDatabases.forEach { it.close() }
    }

    protected fun newDatabase(): CinexDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CinexDatabase::class.java)
            .build()
            .also { extraDatabases += it }

    /** Device A, on the base class database. */
    protected val deviceA: Device by lazy { Device(db, "a") }

    protected fun newDevice(name: String, limits: BackupLimits = BackupLimits()) = Device(newDatabase(), name, limits)

    inner class Device(val db: CinexDatabase, name: String, limits: BackupLimits = BackupLimits()) {
        val root: File = tmp.newFolder(name)
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val documents = FileDocuments()
        var failpoint: (ImportStep) -> Unit = {}
        private val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("backup-test-$name", Context.MODE_PRIVATE)
        val lastExports = LastExportStore(prefs)
        val repository = RoomBackupRepository(
            db = db,
            filesDir = filesDir,
            cacheDir = cacheDir,
            documents = documents,
            lastExportStore = lastExports,
            appVersion = { "1.2.3" },
            readSeed = { seedAsset },
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            limits = limits,
            failpoint = { failpoint(it) },
        )

        /** A document path in this device's "Downloads". */
        fun document(name: String): String = File(root, name).path

        fun writeFile(path: String, bytes: ByteArray = Random.nextBytes(3000)): String {
            File(filesDir, path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
            return path
        }

        /** Every app-private photo and reference image: relative path → Base64 content. */
        fun files(): Map<String, String> = listOf("photos", "reference").flatMap { folder ->
            File(filesDir, folder).listFiles().orEmpty().map { "$folder/${it.name}" to Base64.getEncoder().encodeToString(it.readBytes()) }
        }.toMap()

        suspend fun snapshot(): BackupData = db.readBackupData()

        fun stagingIsEmpty(): Boolean = File(cacheDir, RoomBackupRepository.STAGING_FOLDER).listFiles().isNullOrEmpty()

        fun safetyCopyFiles(): List<String> = File(filesDir, SafetyCopies.FOLDER).listFiles().orEmpty().map { it.name }.sorted()

        suspend fun seed(): Boolean = Seeder(db) { seedAsset }.seedIfNeeded()
    }

    /**
     * Realistic data on device A: the shipped seed (13 cinemas, 36 halls, delphi
     * LUX layouts with seat kinds), a renamed preloaded hall with a reference image,
     * visits with seats, ratings, photos and formats, seat colours and a favourite,
     * a user-added cinema whose hall has seat kinds, an aisle, a seat id mark above
     * its layout's ids and a visit on a seat that was removed since.
     */
    protected suspend fun buildRealisticData() {
        val dev = deviceA
        dev.seed()
        val saal1 = db.hallDao().getAll().single { it.seedKey == "delphi-lux/saal-1" }
        halls.renameHall(saal1.id, "Saal Eins")
        val lux = halls.getHall(saal1.id)!!
        val luxSeats = lux.layout!!.seatIds.sortedBy { it.value }
        halls.updateHall(lux.copy(referenceImagePath = dev.writeFile("reference/hall-${lux.id}-a.jpg"), screenWidth = 12.5f))
        visits.save(
            Visit(
                hallId = lux.id,
                date = LocalDate.of(2026, 9, 12),
                filmTitle = "Perfect Days",
                seatId = luxSeats[3],
                screeningFormat = ScreeningFormat.OMU,
                hallRatings = HallRatings(Rating(5), Rating(4), Rating(4), Rating(3), Rating(2), Rating(5)),
                seatRating = Rating(4),
                cinemaRatings = CinemaRatings(Rating(5), Rating(3), null, Rating(4)),
                note = "Great sound",
                photoPath = dev.writeFile("photos/visit-1.jpg"),
            ),
        )
        visits.save(Visit(hallId = lux.id, date = LocalDate.of(2026, 9, 20), seatId = luxSeats[10], seatRating = Rating(2)))
        overrides.setSeatColour(lux.id, luxSeats[5], SeatColour.RED)
        overrides.setFavouriteSeat(lux.id, luxSeats[3])

        val kino = cinemas.createCinema("Kiezkino", "Weserstraße 1", "Small and cosy")
        val hallId = halls.createHall(kino, "Saal A")
        val empty = halls.getHall(hallId)!!
        val drawn = empty.startLayoutEdit().newGrid(4, 6)
            .setCellType(1, 2, CellType.AISLE)
            .setSeat(2, 0, SeatKind.WHEELCHAIR)
            .setSeat(2, 1, SeatKind.COMPANION)
            .setSeat(0, 5, SeatKind.SOFA)
        halls.updateHall(empty.copy(layout = drawn.layout))
        val lastRowSeat = halls.getHall(hallId)!!.layout!!.rows[3].seatIds[1]
        visits.save(Visit(hallId = hallId, date = LocalDate.of(2026, 8, 1), seatId = lastRowSeat, seatRating = Rating(1)))
        // Remove the last row (the highest ids): they stay used (mark), the visit keeps its seat id.
        val withoutRow = halls.getHall(hallId)!!
        halls.updateHall(withoutRow.copy(layout = withoutRow.startLayoutEdit().removeRow(3).layout))
        val userHall = halls.getHall(hallId)!!
        halls.updateHall(
            userHall.copy(
                screenWidth = 8f,
                screenDistance = 1.5f,
                notes = "Row C is best",
                referenceImagePath = dev.writeFile("reference/hall-$hallId-b.jpg"),
            ),
        )
        overrides.setSeatColour(hallId, userHall.layout!!.seatIds.minBy { it.value }, SeatColour.GREEN)
        visits.save(
            Visit(
                hallId = hallId,
                date = LocalDate.of(2026, 10, 1),
                filmTitle = "Aftersun",
                screeningFormat = ScreeningFormat.OV,
                photoPath = dev.writeFile("photos/visit-2.jpg"),
            ),
        )
    }
}
