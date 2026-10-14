package io.github.katznmeik.cinexperience.core.data.backup

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Opens the documents the user picks with the Storage Access Framework. */
internal interface DocumentAccess {
    /** @throws java.io.IOException or SecurityException if it can't be opened. */
    fun openInput(uri: String): InputStream

    /** Opens for writing, truncating. @throws java.io.IOException or SecurityException. */
    fun openOutput(uri: String): OutputStream

    /** Deletes a (partly written) document; best effort, never throws. */
    fun delete(uri: String)
}

internal class ContentResolverDocuments(private val resolver: ContentResolver) : DocumentAccess {
    override fun openInput(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("Can't open $uri")

    override fun openOutput(uri: String): OutputStream {
        val parsed = Uri.parse(uri)
        // "wt" truncates; some providers only support "w" (a new document is empty anyway).
        val truncating = try {
            resolver.openOutputStream(parsed, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        return truncating ?: resolver.openOutputStream(parsed, "w") ?: throw FileNotFoundException("Can't write $uri")
    }

    override fun delete(uri: String) {
        try {
            DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't delete the incomplete backup file", e)
        }
    }

    private companion object {
        const val TAG = "CinexBackup"
    }
}

/** The last export (date and counts), kept in shared preferences (covered by Auto Backup). */
internal class LastExportStore(private val prefs: SharedPreferences) {
    fun read(): LastExport? {
        val at = prefs.getLong(KEY_AT, -1L)
        if (at < 0) return null
        return LastExport(
            exportedAt = Instant.ofEpochMilli(at),
            counts = BackupCounts(
                cinemas = prefs.getInt(KEY_CINEMAS, 0),
                halls = prefs.getInt(KEY_HALLS, 0),
                visits = prefs.getInt(KEY_VISITS, 0),
                overrides = prefs.getInt(KEY_OVERRIDES, 0),
                photos = prefs.getInt(KEY_PHOTOS, 0),
                referenceImages = prefs.getInt(KEY_REFERENCE_IMAGES, 0),
            ),
        )
    }

    /** Returns false if it couldn't be stored (the export itself still succeeded). */
    fun write(export: LastExport): Boolean = prefs.edit()
        .putLong(KEY_AT, export.exportedAt.toEpochMilli())
        .putInt(KEY_CINEMAS, export.counts.cinemas)
        .putInt(KEY_HALLS, export.counts.halls)
        .putInt(KEY_VISITS, export.counts.visits)
        .putInt(KEY_OVERRIDES, export.counts.overrides)
        .putInt(KEY_PHOTOS, export.counts.photos)
        .putInt(KEY_REFERENCE_IMAGES, export.counts.referenceImages)
        .commit()

    companion object {
        const val PREFERENCES_NAME: String = "backup"
        private const val KEY_AT = "last_export_at"
        private const val KEY_CINEMAS = "last_export_cinemas"
        private const val KEY_HALLS = "last_export_halls"
        private const val KEY_VISITS = "last_export_visits"
        private const val KEY_OVERRIDES = "last_export_overrides"
        private const val KEY_PHOTOS = "last_export_photos"
        private const val KEY_REFERENCE_IMAGES = "last_export_reference_images"
    }
}

/**
 * Automatic copies of the data taken right before an import replaces it, as
 * backup zips in `filesDir/backups/pre-import-<UTC timestamp>.zip`. Only the
 * newest [keep] are kept. App-private and not part of Android Auto Backup.
 */
internal class SafetyCopies(private val dir: File, private val keep: Int = KEEP) {

    class Copy(val file: File, val createdAt: Instant)

    fun all(): List<Copy> = dir.listFiles().orEmpty()
        .mapNotNull { file -> parse(file.name)?.let { Copy(file, it) } }
        .sortedByDescending { it.createdAt }

    fun latest(): Copy? = all().firstOrNull()

    /** The file for a copy taken at [at] (not created yet); never an existing file. */
    fun fileFor(at: Instant): File {
        var time = at
        while (true) {
            val file = File(dir, "$PREFIX${STAMP.format(time)}$SUFFIX")
            if (!file.exists()) return file
            time = time.plusMillis(1)
        }
    }

    /** Deletes all but the newest [keep] copies, and leftover temporary files. */
    fun prune() {
        all().drop(keep).forEach { it.file.delete() }
        dir.listFiles().orEmpty().filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
    }

    private fun parse(name: String): Instant? {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
        return try {
            Instant.from(STAMP.parse(name.removePrefix(PREFIX).removeSuffix(SUFFIX)))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    companion object {
        const val FOLDER: String = "backups"
        const val KEEP: Int = 2
        const val TEMP_SUFFIX: String = ".tmp"
        private const val PREFIX = "pre-import-"
        private const val SUFFIX = ".zip"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
    }
}
