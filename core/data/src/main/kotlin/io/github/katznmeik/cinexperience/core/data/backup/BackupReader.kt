package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A staged file of a backup: its unpacked copy and size. */
internal data class StagedFile(val file: File, val size: Long)

/** A backup that was unpacked into [dir] and fully validated. Nothing outside [dir] was touched. */
internal class StagedBackup(
    val id: String,
    val dir: File,
    val manifest: BackupManifest,
    val exportedAt: Instant,
    val data: BackupData,
    /** Every file [data] refers to (zip path → staged copy). Unreferenced zip files are not kept. */
    val files: Map<String, StagedFile>,
) {
    fun preview(source: ImportSource) = ImportPreview(
        id = id,
        source = source,
        exportedAt = exportedAt,
        appVersion = manifest.appVersion,
        backupVersion = manifest.backupVersion,
        counts = data.counts(),
    )

    /** True if every staged file is still there, unchanged in size (the cache may have been cleared). */
    fun filesIntact(): Boolean = files.values.all { it.file.isFile && it.file.length() == it.size }
}

/**
 * Unpacks and validates a backup zip (see BackupFormat.kt), streaming every entry
 * into a staging directory. Throws [BackupException] for anything unexpected:
 *
 * - **Zip safety:** only `manifest.json`, `data.json`, `photos/<name>` and
 *   `reference/<name>` (plus those two folder entries) are allowed; absolute paths,
 *   `..`, backslashes, nested folders, odd characters and duplicate names are
 *   rejected; entry count and sizes are capped by [BackupLimits] while reading
 *   (the sizes a zip declares are not trusted).
 * - **Manifest:** `format` must match; a newer `backupVersion` is reported as such;
 *   older versions go through [BackupMigrations].
 * - **Data:** strict JSON; unique, positive ids; halls → cinemas, visits → halls,
 *   overrides → halls and a seat of that hall's layout; at most one favourite per
 *   hall; every layout must decode with this app's layout reader (a layout it
 *   can't read is rejected, never imported silently); known enum keys; ratings
 *   1–5; valid dates; counts equal the manifest's; every referenced photo and
 *   reference image is in the zip under a safe name.
 */
internal class BackupReader(private val limits: BackupLimits = BackupLimits()) {

    /** Unpacks [input] into [dir] (created; the caller deletes it on failure) and validates it. */
    fun read(input: InputStream, dir: File): StagedBackup {
        if (!dir.mkdirs() && !dir.isDirectory) throw writeFailed(null)
        val unpacked = unpack(input, dir)
        val manifestBytes = unpacked.manifest ?: throw notABackup()
        val manifest = parseManifest(manifestBytes)
        val dataFile = unpacked.dataFile ?: throw damaged("data.json is missing.")
        val data = parseData(dataFile, manifest.backupVersion)
        validate(data, unpacked.files.keys, manifest.counts)
        val referenced = data.filePaths
        // Files in the zip that nothing refers to are not imported.
        unpacked.files.filterKeys { it !in referenced }.values.forEach { it.file.delete() }
        return StagedBackup(
            id = UUID.randomUUID().toString(),
            dir = dir,
            manifest = manifest,
            exportedAt = Instant.parse(manifest.exportedAt),
            data = data,
            files = unpacked.files.filterKeys { it in referenced },
        )
    }

    private class Unpacked(val manifest: ByteArray?, val dataFile: File?, val files: Map<String, StagedFile>)

    private fun unpack(input: InputStream, dir: File): Unpacked {
        val names = HashSet<String>()
        val files = HashMap<String, StagedFile>()
        var manifest: ByteArray? = null
        var dataFile: File? = null
        var entries = 0
        val budget = Budget(limits.maxTotalBytes)
        val zip = ZipInputStream(BufferedInputStream(input, BUFFER_SIZE))
        while (true) {
            val entry = nextEntry(zip) ?: break
            if (++entries > limits.maxEntries) throw tooLarge()
            val name = entry.name
            if (!names.add(name)) throw unsafe("It contains “${name.take(80)}” twice.")
            when {
                entry.isDirectory -> {
                    if (name.removeSuffix("/") !in BackupFormat.FILE_FOLDERS) throw unsafe("It contains an unexpected folder.")
                }
                name == BackupFormat.MANIFEST -> {
                    val target = File(dir, MANIFEST_FILE)
                    copyEntry(zip, target, limits.maxManifestBytes, budget)
                    val bytes = target.readBytes()
                    // Check the format and version right away, before unpacking possibly gigabytes.
                    parseManifest(bytes)
                    manifest = bytes
                }
                name == BackupFormat.DATA -> {
                    val target = File(dir, DATA_FILE)
                    copyEntry(zip, target, limits.maxDataBytes, budget)
                    dataFile = target
                }
                BackupFormat.isFilePath(name) -> {
                    val target = File(File(dir, FILES_DIR), name)
                    // Defence in depth: isFilePath already rules out anything that could escape.
                    if (!target.canonicalPath.startsWith(File(dir, FILES_DIR).canonicalPath + File.separator)) {
                        throw unsafe("It contains an unsafe file name.")
                    }
                    files[name] = StagedFile(target, copyEntry(zip, target, limits.maxFileBytes, budget))
                }
                else -> throw unsafe("It contains an unexpected or unsafe file name: “${name.take(80)}”.")
            }
        }
        return Unpacked(manifest, dataFile, files)
    }

    private fun nextEntry(zip: ZipInputStream): ZipEntry? = try {
        zip.nextEntry
    } catch (e: EOFException) {
        throw BackupException(BackupError.NOT_A_BACKUP, "The backup file is incomplete.", e)
    } catch (e: ZipException) {
        // Includes Android's own zip path validation ("Invalid zip entry path").
        throw BackupException(BackupError.NOT_A_BACKUP, "This file isn't a valid backup (the zip file is damaged or unsafe).", e)
    } catch (e: IllegalArgumentException) { // malformed entry name
        throw BackupException(BackupError.NOT_A_BACKUP, "This file isn't a valid backup (the zip file is damaged).", e)
    } catch (e: IOException) {
        throw readFailed(e)
    }

    /** Remaining bytes for the whole archive. */
    private class Budget(var remaining: Long)

    /**
     * Streams the current entry into [target] (fsynced), counting the bytes really
     * read; throws as soon as [maxBytes] or the [budget] is exceeded. Returns the size.
     */
    private fun copyEntry(zip: ZipInputStream, target: File, maxBytes: Long, budget: Budget): Long {
        target.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw writeFailed(null) }
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        val out = try {
            FileOutputStream(target)
        } catch (e: IOException) {
            throw writeFailed(e)
        }
        out.use {
            while (true) {
                val read = try {
                    zip.read(buffer)
                } catch (e: EOFException) {
                    throw BackupException(BackupError.NOT_A_BACKUP, "The backup file is incomplete.", e)
                } catch (e: ZipException) {
                    throw BackupException(BackupError.NOT_A_BACKUP, "This file isn't a valid backup (the zip file is damaged).", e)
                } catch (e: IOException) {
                    throw readFailed(e)
                }
                if (read < 0) break
                size += read
                budget.remaining -= read
                if (size > maxBytes || budget.remaining < 0) throw tooLarge()
                try {
                    out.write(buffer, 0, read)
                } catch (e: IOException) {
                    throw writeFailed(e)
                }
            }
            try {
                out.flush()
                out.fd.sync()
            } catch (e: IOException) {
                throw writeFailed(e)
            }
        }
        return size
    }

    private fun parseManifest(bytes: ByteArray): BackupManifest {
        val obj = try {
            BackupFormat.manifestJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw notABackup()
        if ((obj["format"] as? JsonPrimitive)?.contentOrNull != BackupFormat.FORMAT_ID) throw notABackup()
        val version = (obj["backupVersion"] as? JsonPrimitive)?.intOrNull ?: throw damaged("its manifest has no version.")
        if (version > BackupFormat.BACKUP_VERSION) {
            throw BackupException(
                BackupError.NEWER_VERSION,
                "This backup was made by a newer version of Cinexperience. Update the app to import it.",
            )
        }
        if (version < 1) throw damaged("its manifest has an invalid version.")
        val manifest = try {
            BackupFormat.manifestJson.decodeFromJsonElement(BackupManifest.serializer(), obj)
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw damaged("its manifest is incomplete.", e)
        }
        try {
            Instant.parse(manifest.exportedAt)
        } catch (e: DateTimeParseException) {
            throw damaged("its manifest has an invalid date.", e)
        }
        return manifest
    }

    private fun parseData(file: File, manifestVersion: Int): BackupData {
        val obj = try {
            BackupFormat.strictJson.parseToJsonElement(file.readText()) as? JsonObject
        } catch (e: SerializationException) {
            throw damaged("data.json can't be read.", e)
        } catch (e: IOException) {
            throw readFailed(e)
        } ?: throw damaged("data.json can't be read.")
        if ((obj["backupVersion"] as? JsonPrimitive)?.intOrNull != manifestVersion) {
            throw damaged("data.json and the manifest have different versions.")
        }
        val migrated = BackupMigrations.migrate(obj, manifestVersion)
        return try {
            BackupFormat.strictJson.decodeFromJsonElement(BackupData.serializer(), migrated)
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw damaged("data.json has missing or unexpected fields.", e)
        }
    }

    private fun validate(data: BackupData, archiveFiles: Set<String>, manifestCounts: BackupCountsJson) {
        val cinemaIds = HashSet<Long>()
        val cinemaKeys = HashSet<String>()
        for (cinema in data.cinemas) {
            if (cinema.id <= 0 || !cinemaIds.add(cinema.id)) throw damaged("cinema id ${cinema.id} is invalid or used twice.")
            if (cinema.name.isBlank()) throw damaged("cinema ${cinema.id} has no name.")
            cinema.seedKey?.let { if (it.isBlank() || !cinemaKeys.add(it)) throw damaged("cinema key “$it” is invalid or used twice.") }
        }

        val layouts = HashMap<Long, SeatLayout?>()
        val hallKeys = HashSet<String>()
        for (hall in data.halls) {
            if (hall.id <= 0 || hall.id in layouts) throw damaged("hall id ${hall.id} is invalid or used twice.")
            if (hall.name.isBlank()) throw damaged("hall ${hall.id} has no name.")
            val label = "hall “${hall.name}”"
            if (hall.cinemaId !in cinemaIds) throw damaged("$label belongs to a cinema that isn't in the backup.")
            hall.seedKey?.let { if (it.isBlank() || !hallKeys.add(it)) throw damaged("hall key “$it” is invalid or used twice.") }
            if (hall.nextSeatId < SeatLayout.FIRST_SEAT_ID) throw damaged("$label has an invalid seat id mark.")
            if (hall.screenWidth?.isFinite() == false || hall.screenDistance?.isFinite() == false) {
                throw damaged("$label has an invalid screen size.")
            }
            layouts[hall.id] = hall.layoutJson?.let { json ->
                try {
                    LayoutJson.decode(json)
                } catch (e: IllegalArgumentException) { // includes SerializationException
                    throw damaged("the seat layout of $label can't be read by this app version.", e)
                }
            }
            hall.referenceImagePath?.let { checkFile(it, ReferenceImageStore.FOLDER, archiveFiles, "the reference image of $label") }
        }

        val visitIds = HashSet<Long>()
        for (visit in data.visits) {
            if (visit.id <= 0 || !visitIds.add(visit.id)) throw damaged("visit id ${visit.id} is invalid or used twice.")
            val label = "visit ${visit.id}"
            if (visit.hallId !in layouts) throw damaged("$label belongs to a hall that isn't in the backup.")
            try {
                LocalDate.parse(visit.date)
            } catch (e: DateTimeParseException) {
                throw damaged("$label has an invalid date.", e)
            }
            if (visit.seatId != null && visit.seatId < SeatLayout.FIRST_SEAT_ID) throw damaged("$label has an invalid seat.")
            val ratings = visit.hallRatings.all + visit.cinemaRatings.all + visit.seatRating
            if (ratings.any { it != null && it !in RATING_RANGE }) throw damaged("$label has a rating outside 1–5.")
            visit.screeningFormat?.let { key ->
                if (ScreeningFormat.entries.none { it.storageKey == key }) throw damaged("$label has an unknown screening format.")
            }
            visit.photoPath?.let { checkFile(it, VisitPhotoStore.FOLDER, archiveFiles, "the photo of $label") }
        }

        val overrideKeys = HashSet<Pair<Long, Int>>()
        val favouriteHalls = HashSet<Long>()
        val hallNames = data.halls.associate { it.id to it.name }
        for (override in data.seatOverrides) {
            if (override.hallId !in layouts) throw damaged("a seat colour belongs to a hall that isn't in the backup.")
            val label = "hall “${hallNames[override.hallId]}”"
            if (!overrideKeys.add(override.hallId to override.seatId)) throw damaged("a seat of $label has two colour entries.")
            val layout = layouts[override.hallId]
            if (layout == null || SeatId(override.seatId) !in layout.seatIds) {
                throw damaged("a seat colour of $label refers to a seat that isn't in its layout.")
            }
            override.colour?.let { key ->
                if (SeatColour.entries.none { it.storageKey == key }) throw damaged("a seat of $label has an unknown colour.")
            }
            if (override.isFavourite && !favouriteHalls.add(override.hallId)) throw damaged("$label has two favourite seats.")
        }

        val snapshotKeys = HashSet<Triple<String, String, String>>()
        for (snapshot in data.seedSnapshots) {
            if (snapshot.kind.isBlank() || snapshot.seedKey.isBlank() || snapshot.field.isBlank() ||
                !snapshotKeys.add(Triple(snapshot.kind, snapshot.seedKey, snapshot.field))
            ) {
                throw damaged("its seed records are invalid.")
            }
        }
        val metadataKeys = HashSet<String>()
        for (entry in data.metadata) {
            if (entry.key.isBlank() || !metadataKeys.add(entry.key)) throw damaged("its metadata is invalid.")
        }
        data.metadata.firstOrNull { it.key == Seeder.KEY_SEED_VERSION }?.let {
            if ((it.value.toIntOrNull() ?: -1) < 0) throw damaged("its seed version is invalid.")
        }

        if (data.counts().toJson() != manifestCounts) throw damaged("its contents don't match its manifest.")
    }

    private fun checkFile(path: String, folder: String, archiveFiles: Set<String>, what: String) {
        if (!BackupFormat.isFilePath(path, folder)) throw damaged("$what has an unsafe file name.")
        if (path !in archiveFiles) {
            throw BackupException(BackupError.MISSING_FILE, "The backup is incomplete: $what ($path) is missing.")
        }
    }

    private fun notABackup() = BackupException(BackupError.NOT_A_BACKUP, "This file isn't a Cinexperience backup.")

    private fun damaged(detail: String, cause: Throwable? = null) =
        BackupException(BackupError.INVALID_DATA, "The backup is damaged: $detail", cause)

    private fun unsafe(detail: String) =
        BackupException(BackupError.UNSAFE_ARCHIVE, "This backup can't be imported safely. $detail")

    private fun tooLarge() =
        BackupException(BackupError.TOO_LARGE, "This backup is too large or has too many files to import safely.")

    private fun readFailed(cause: Throwable?) =
        BackupException(BackupError.READ_FAILED, "Couldn't read the backup file.", cause)

    private fun writeFailed(cause: Throwable?) =
        BackupException(BackupError.WRITE_FAILED, "Couldn't unpack the backup. Is there enough free storage?", cause)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MANIFEST_FILE = "manifest.json"
        const val DATA_FILE = "data.json"
        const val FILES_DIR = "files"
        val RATING_RANGE = 1..5
    }
}
