package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.LayoutTooLargeException
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.DataLimits
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.ScreenSizeRules
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.TextLimits
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_LONG_TEXT_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_NAME_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_ROW_LABEL_LENGTH
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
import java.io.OutputStream
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
    /** Every file [data] refers to (zip path → staged copy). Unreferenced zip entries are never staged. */
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
 * Data that breaks a rule of the backup format. [detail] completes a sentence
 * such as "The backup is damaged: …" or "Your data can't be backed up: …".
 */
internal class ValidationProblem(val detail: String, val missingFile: Boolean = false, cause: Throwable? = null) :
    Exception(detail, cause)

/**
 * The rules every backup's data must follow, checked on import (before anything
 * is touched) and on export (so an export never produces a backup that can't be
 * imported):
 *
 * unique, positive ids; non-blank names; unique seed keys; halls → cinemas,
 * visits → halls, overrides → halls and a seat of that hall's layout; at most
 * one favourite per hall; every layout decodes with this app's layout reader (a
 * layout it can't read is rejected, never carried along silently); known enum
 * keys; ratings 1–5; valid dates; a seat id mark in 1..[SeatLayout.MAX_SEAT_ID]
 * and visit seat ids below it; a screen width and distance that
 * [ScreenSizeRules] accepts (positive and at most [ScreenSizeRules.MAX_WIDTH],
 * 0 to [ScreenSizeRules.MAX_DISTANCE]); a screen offset that
 * [ScreenOffsetRules.isValid] accepts (finite, 0.5 steps, within
 * ±[ScreenOffsetRules.MAX_MAGNITUDE]); every referenced photo / reference image
 * has a safe name in its folder and is in [files].
 *
 * Size limits, so no imported row is too large for Android to read (2 MB per
 * row) or slows every list down: at most [DataLimits.MAX_HALLS] halls; names, addresses and keys at most
 * [TextLimits.MAX_NAME_LENGTH] code points, notes and film titles at most
 * [TextLimits.MAX_LONG_TEXT_LENGTH], row labels at most
 * [TextLimits.MAX_ROW_LABEL_LENGTH]; layouts within [SeatLayout.MAX_ROWS] ×
 * [SeatLayout.MAX_COLUMNS], their JSON at most [LayoutJson.MAX_JSON_LENGTH]
 * chars and not much longer than this app's own encoding of the layout
 * ([LayoutJson.isPadded]); seed record and metadata values at most
 * [LayoutJson.MAX_JSON_LENGTH] chars; file names are bounded by their pattern.
 * The repositories enforce the same rules for every value they write (texts,
 * row labels, screen size and offset, the number of halls; the seeder stops at
 * that number too), so data saved by the app always exports.
 */
internal object BackupValidator {

    /** @throws ValidationProblem for the first broken rule. */
    fun validate(data: BackupData, files: Set<String>) {
        val cinemaIds = HashSet<Long>()
        val cinemaKeys = HashSet<String>()
        for (cinema in data.cinemas) {
            if (cinema.id <= 0 || !cinemaIds.add(cinema.id)) problem("cinema id ${cinema.id} is invalid or used twice.")
            if (cinema.name.isBlank()) problem("cinema ${cinema.id} has no name.")
            if (!fitsName(cinema.name)) problem("the name of cinema ${cinema.id} is too long.")
            val label = "cinema “${cinema.name}”"
            if (!fitsName(cinema.address)) problem("the address of $label is too long.")
            if (!fitsLongText(cinema.notes)) problem("the notes of $label are too long.")
            cinema.seedKey?.let {
                if (!fitsName(it)) problem("a cinema key is too long.")
                if (it.isBlank() || !cinemaKeys.add(it)) problem("cinema key “$it” is invalid or used twice.")
            }
        }

        if (data.halls.size > DataLimits.MAX_HALLS) problem("it has more than ${DataLimits.MAX_HALLS_TEXT} halls.")
        val layouts = HashMap<Long, SeatLayout?>()
        val hallKeys = HashSet<String>()
        for (hall in data.halls) {
            if (hall.id <= 0 || hall.id in layouts) problem("hall id ${hall.id} is invalid or used twice.")
            if (hall.name.isBlank()) problem("hall ${hall.id} has no name.")
            if (!fitsName(hall.name)) problem("the name of hall ${hall.id} is too long.")
            val label = "hall “${hall.name}”"
            if (hall.cinemaId !in cinemaIds) problem("$label belongs to a cinema that isn't there.")
            hall.seedKey?.let {
                if (!fitsName(it)) problem("a hall key is too long.")
                if (it.isBlank() || !hallKeys.add(it)) problem("hall key “$it” is invalid or used twice.")
            }
            if (!fitsLongText(hall.notes)) problem("the notes of $label are too long.")
            if (hall.nextSeatId !in SeatLayout.FIRST_SEAT_ID..SeatLayout.MAX_SEAT_ID) problem("$label has an invalid seat id mark.")
            if (!ScreenSizeRules.isValidWidth(hall.screenWidth) || !ScreenSizeRules.isValidDistance(hall.screenDistance)) {
                problem("$label has an invalid screen size.")
            }
            if (!ScreenOffsetRules.isValid(hall.screenOffset)) problem("$label has an invalid screen position.")
            layouts[hall.id] = hall.layoutJson?.let { json ->
                val layout = try {
                    LayoutJson.decode(json)
                } catch (e: LayoutTooLargeException) { // JSON, grid or seat ids beyond the limits
                    problem("the seat layout of $label is too large.", e)
                } catch (e: IllegalArgumentException) { // includes SerializationException
                    problem("the seat layout of $label can't be read by this app version.", e)
                } catch (e: StackOverflowError) {
                    problem("the seat layout of $label can't be read by this app version.")
                }
                if (LayoutJson.isPadded(json, layout)) problem("the seat layout of $label is too large.")
                if (layout.rows.any { !TextLimits.fits(it.label, MAX_ROW_LABEL_LENGTH) }) {
                    problem("a row label in the seat layout of $label is too long.")
                }
                layout
            }
            hall.referenceImagePath?.let { checkFile(it, ReferenceImageStore.FOLDER, files, "the reference image of $label") }
        }

        val visitIds = HashSet<Long>()
        for (visit in data.visits) {
            if (visit.id <= 0 || !visitIds.add(visit.id)) problem("visit id ${visit.id} is invalid or used twice.")
            val label = "visit ${visit.id}"
            if (visit.hallId !in layouts) problem("$label belongs to a hall that isn't there.")
            try {
                LocalDate.parse(visit.date)
            } catch (e: DateTimeParseException) {
                problem("$label has an invalid date.", e)
            }
            // A seat id is always below its layout's nextSeatId, so below MAX_SEAT_ID.
            if (visit.seatId != null && visit.seatId !in SeatLayout.FIRST_SEAT_ID until SeatLayout.MAX_SEAT_ID) {
                problem("$label has an invalid seat.")
            }
            if (!fitsLongText(visit.filmTitle)) problem("the film title of $label is too long.")
            if (!fitsLongText(visit.note)) problem("the note of $label is too long.")
            val ratings = visit.hallRatings.all + visit.cinemaRatings.all + visit.seatRating
            if (ratings.any { it != null && it !in RATING_RANGE }) problem("$label has a rating outside 1–5.")
            visit.screeningFormat?.let { key ->
                if (ScreeningFormat.entries.none { it.storageKey == key }) problem("$label has an unknown screening format.")
            }
            visit.photoPath?.let { checkFile(it, VisitPhotoStore.FOLDER, files, "the photo of $label") }
        }

        val overrideKeys = HashSet<Pair<Long, Int>>()
        val favouriteHalls = HashSet<Long>()
        val hallNames = data.halls.associate { it.id to it.name }
        for (override in data.seatOverrides) {
            if (override.hallId !in layouts) problem("a seat colour belongs to a hall that isn't there.")
            val label = "hall “${hallNames[override.hallId]}”"
            if (!overrideKeys.add(override.hallId to override.seatId)) problem("a seat of $label has two colour entries.")
            val layout = layouts[override.hallId]
            if (layout == null || SeatId(override.seatId) !in layout.seatIds) {
                problem("a seat colour of $label refers to a seat that isn't in its layout.")
            }
            override.colour?.let { key ->
                if (SeatColour.entries.none { it.storageKey == key }) problem("a seat of $label has an unknown colour.")
            }
            if (override.isFavourite && !favouriteHalls.add(override.hallId)) problem("$label has two favourite seats.")
        }

        val snapshotKeys = HashSet<Triple<String, String, String>>()
        for (snapshot in data.seedSnapshots) {
            if (snapshot.kind.isBlank() || snapshot.seedKey.isBlank() || snapshot.field.isBlank() ||
                !fitsName(snapshot.kind) || !fitsName(snapshot.seedKey) || !fitsName(snapshot.field) ||
                (snapshot.value?.length ?: 0) > LayoutJson.MAX_JSON_LENGTH ||
                !snapshotKeys.add(Triple(snapshot.kind, snapshot.seedKey, snapshot.field))
            ) {
                problem("its seed records are invalid.")
            }
        }
        val metadataKeys = HashSet<String>()
        for (entry in data.metadata) {
            if (entry.key.isBlank() || !fitsName(entry.key) || entry.value.length > LayoutJson.MAX_JSON_LENGTH ||
                !metadataKeys.add(entry.key)
            ) {
                problem("its metadata is invalid.")
            }
        }
        data.metadata.firstOrNull { it.key == Seeder.KEY_SEED_VERSION }?.let {
            if ((it.value.toIntOrNull() ?: -1) < 0) problem("its seed version is invalid.")
        }
    }

    private fun checkFile(path: String, folder: String, files: Set<String>, what: String) {
        if (!BackupFormat.isFilePath(path, folder)) problem("$what has an unsafe file name.")
        if (path !in files) throw ValidationProblem("$what ($path) is missing.", missingFile = true)
    }

    private fun problem(detail: String, cause: Throwable? = null): Nothing = throw ValidationProblem(detail, cause = cause)

    private fun fitsName(text: String?): Boolean = TextLimits.fits(text, MAX_NAME_LENGTH)

    private fun fitsLongText(text: String?): Boolean = TextLimits.fits(text, MAX_LONG_TEXT_LENGTH)

    private val RATING_RANGE = 1..5
}

/**
 * Unpacks and validates a backup zip (see BackupFormat.kt), streaming entries
 * into a staging directory. Throws [BackupException] for anything unexpected:
 *
 * - **Zip safety:** only `manifest.json`, `data.json`, `photos/<name>` and
 *   `reference/<name>` (plus those two folder entries) are allowed; absolute paths,
 *   `..`, backslashes, nested folders, odd characters and duplicate names are
 *   rejected. `manifest.json` and `data.json` must come before any file entry, so
 *   only files the data refers to are staged; other entries are read and
 *   discarded. Entry count and sizes are capped by [BackupLimits] while reading
 *   (the sizes a zip declares are not trusted), for skipped entries too.
 * - **Manifest:** `format` must match; a newer `backupVersion` is reported as such;
 *   older versions go through [BackupMigrations].
 * - **Data:** strict JSON, decoded straight into the DTOs (no generic JSON tree,
 *   so deeply nested input can't exhaust the stack); then [BackupValidator]; the
 *   manifest's counts must match.
 */
internal class BackupReader(private val limits: BackupLimits = BackupLimits()) {

    /** Unpacks [input] into [dir] (created; the caller deletes it on failure) and validates it. */
    fun read(input: InputStream, dir: File): StagedBackup {
        if (!dir.mkdirs() && !dir.isDirectory) throw writeFailed(null)
        val unpacked = unpack(input, dir)
        try {
            BackupValidator.validate(unpacked.data, unpacked.files.keys)
        } catch (p: ValidationProblem) {
            throw if (p.missingFile) {
                BackupException(BackupError.MISSING_FILE, "The backup is incomplete: ${p.detail}", p)
            } else {
                damaged(p.detail, p)
            }
        }
        if (unpacked.data.counts().toJson() != unpacked.manifest.counts) throw damaged("its contents don't match its manifest.")
        return StagedBackup(
            id = UUID.randomUUID().toString(),
            dir = dir,
            manifest = unpacked.manifest,
            exportedAt = Instant.parse(unpacked.manifest.exportedAt),
            data = unpacked.data,
            files = unpacked.files,
        )
    }

    /** The manifest of a backup (e.g. a safety copy's counts), or `null` if it can't be read. */
    fun readManifestOrNull(input: InputStream): BackupManifest? = try {
        val zip = ZipInputStream(BufferedInputStream(input, BUFFER_SIZE))
        var manifest: BackupManifest? = null
        while (manifest == null) {
            val entry = zip.nextEntry ?: break
            if (entry.name == BackupFormat.MANIFEST) manifest = parseManifest(readCapped(zip, limits.maxManifestBytes))
        }
        manifest
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    /** The data of a backup we wrote ourselves (a safety copy), or `null` if it can't be read. */
    fun readDataOrNull(input: InputStream): BackupData? = try {
        val zip = ZipInputStream(BufferedInputStream(input, BUFFER_SIZE))
        var manifest: BackupManifest? = null
        var data: BackupData? = null
        while (data == null) {
            val entry = zip.nextEntry ?: break
            when (entry.name) {
                BackupFormat.MANIFEST -> manifest = parseManifest(readCapped(zip, limits.maxManifestBytes))
                BackupFormat.DATA -> data = manifest?.let { parseData(readCapped(zip, limits.maxDataBytes).decodeToString(), it.backupVersion) }
            }
        }
        data
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private class Unpacked(val manifest: BackupManifest, val data: BackupData, val files: Map<String, StagedFile>)

    private fun unpack(input: InputStream, dir: File): Unpacked {
        val names = HashSet<String>()
        val files = HashMap<String, StagedFile>()
        var manifest: BackupManifest? = null
        var dataFile: File? = null
        var data: BackupData? = null
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
                    // Check the format and version right away, before reading anything else.
                    manifest = parseManifest(target.readBytes())
                }
                name == BackupFormat.DATA -> {
                    val target = File(dir, DATA_FILE)
                    copyEntry(zip, target, limits.maxDataBytes, budget)
                    dataFile = target
                }
                BackupFormat.isFilePath(name) -> {
                    val current = data ?: run {
                        // The data must be known first, so only referenced files are staged.
                        val m = manifest ?: throw notABackup()
                        val file = dataFile ?: throw damaged("data.json must come before the photos.")
                        parseData(file, m.backupVersion).also { data = it }
                    }
                    if (name in current.filePaths) {
                        val target = File(File(dir, FILES_DIR), name)
                        // Defence in depth: isFilePath already rules out anything that could escape.
                        if (!target.canonicalPath.startsWith(File(dir, FILES_DIR).canonicalPath + File.separator)) {
                            throw unsafe("It contains an unsafe file name.")
                        }
                        files[name] = StagedFile(target, copyEntry(zip, target, limits.maxFileBytes, budget))
                    } else {
                        drainEntry(zip, limits.maxFileBytes, budget)
                    }
                }
                else -> throw unsafe("It contains an unexpected or unsafe file name: “${name.take(80)}”.")
            }
        }
        val m = manifest ?: throw notABackup()
        val d = data ?: parseData(dataFile ?: throw damaged("data.json is missing."), m.backupVersion)
        return Unpacked(m, d, files)
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

    /** Reads the current entry into memory, at most [maxBytes] (for our own small JSON entries). */
    private fun readCapped(zip: ZipInputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        pump(zip, out, maxBytes, Budget(maxBytes))
        return out.toByteArray()
    }

    /**
     * Streams the current entry into [target] (fsynced), counting the bytes really
     * read; throws as soon as [maxBytes] or the [budget] is exceeded. Returns the size.
     */
    private fun copyEntry(zip: ZipInputStream, target: File, maxBytes: Long, budget: Budget): Long {
        target.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw writeFailed(null) }
        val out = try {
            FileOutputStream(target)
        } catch (e: IOException) {
            throw writeFailed(e)
        }
        return out.use {
            val size = pump(zip, out, maxBytes, budget)
            try {
                out.flush()
                out.fd.sync()
            } catch (e: IOException) {
                throw writeFailed(e)
            }
            size
        }
    }

    /** Reads and discards the current entry, under the same limits as a staged one. */
    private fun drainEntry(zip: ZipInputStream, maxBytes: Long, budget: Budget) {
        pump(zip, OutputStream.nullOutputStream(), maxBytes, budget)
    }

    private fun pump(zip: ZipInputStream, out: OutputStream, maxBytes: Long, budget: Budget): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
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
            if (read < 0) return size
            size += read
            budget.remaining -= read
            if (size > maxBytes || budget.remaining < 0) throw tooLarge()
            try {
                out.write(buffer, 0, read)
            } catch (e: IOException) {
                throw writeFailed(e)
            }
        }
    }

    private fun parseManifest(bytes: ByteArray): BackupManifest {
        val obj = try {
            BackupFormat.manifestJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: StackOverflowError) { // absurdly nested JSON
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
        val text = try {
            file.readText()
        } catch (e: IOException) {
            throw readFailed(e)
        }
        return parseData(text, manifestVersion)
    }

    private fun parseData(text: String, manifestVersion: Int): BackupData {
        val data = try {
            if (manifestVersion == BackupFormat.BACKUP_VERSION) {
                // Straight into the DTOs: any unexpected nesting fails at its first token.
                BackupFormat.strictJson.decodeFromString(BackupData.serializer(), text)
            } else {
                val obj = BackupFormat.strictJson.parseToJsonElement(text) as? JsonObject
                    ?: throw damaged("data.json can't be read.")
                if ((obj["backupVersion"] as? JsonPrimitive)?.intOrNull != manifestVersion) {
                    throw damaged("data.json and the manifest have different versions.")
                }
                BackupFormat.strictJson.decodeFromJsonElement(BackupData.serializer(), BackupMigrations.migrate(obj, manifestVersion))
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw damaged("data.json has missing, unexpected or malformed fields.", e)
        } catch (e: StackOverflowError) {
            throw damaged("data.json is malformed.")
        }
        if (data.backupVersion != BackupFormat.BACKUP_VERSION) throw damaged("data.json and the manifest have different versions.")
        return data
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
    }
}
