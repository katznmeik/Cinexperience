package io.github.katznmeik.cinexperience.core.data.backup

import android.util.Log
import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Every row of the database, read in one transaction (a consistent snapshot). */
internal suspend fun CinexDatabase.readBackupData(): BackupData = withTransaction { readBackupDataInTransaction() }

/** Like [readBackupData]; call inside a transaction. */
internal suspend fun CinexDatabase.readBackupDataInTransaction(): BackupData {
    val dao = backupDao()
    return BackupData(
        backupVersion = BackupFormat.BACKUP_VERSION,
        cinemas = dao.cinemas().map { it.toBackup() },
        halls = dao.halls().map { it.toBackup() },
        visits = dao.visits().map { it.toBackup() },
        seatOverrides = dao.seatOverrides().map { it.toBackup() },
        seedSnapshots = dao.seedSnapshots().map { it.toBackup() },
        metadata = dao.metadata().map { it.toBackup() },
    )
}

/**
 * A file that [BackupWriter.prepare] found was deleted before [BackupWriter.write]
 * got to it (e.g. a visit saved with another photo while an export runs). The
 * backup can't be completed: an import refuses one that lacks a referenced file.
 */
internal class FileDeletedDuringBackupException(path: String, cause: Throwable) :
    IOException("$path was deleted while the backup was being written", cause)

/** Data ready to be written: missing files left out, validated like an import. */
internal class PreparedBackup(val data: BackupData) {
    val counts: BackupCounts = data.counts()
}

/** Writes backup zips (export and pre-import safety copies). */
internal class BackupWriter(private val filesDir: File) {

    /**
     * Prepares [data] for writing. A reference to a file that is missing on disk
     * (or has an unsafe path) is left out (written as `null`) and logged, so one
     * lost photo can't block every export; the app shows such a photo as missing
     * anyway. Then the data is checked with the import's [BackupValidator], so a
     * backup that this app couldn't import again is never written.
     * @throws ValidationProblem naming the first problem.
     */
    fun prepare(data: BackupData): PreparedBackup {
        val available = data.filePaths.filterTo(HashSet()) { path ->
            BackupFormat.isFilePath(path) && File(filesDir, path).isFile
        }
        val missing = data.filePaths - available
        if (missing.isNotEmpty()) Log.w(TAG, "Backup leaves out ${missing.size} missing file(s): $missing")
        val written = data.copy(
            halls = data.halls.map { if (it.referenceImagePath in missing) it.copy(referenceImagePath = null) else it },
            visits = data.visits.map { if (it.photoPath in missing) it.copy(photoPath = null) else it },
        )
        BackupValidator.validate(written, available)
        return PreparedBackup(written)
    }

    /**
     * Writes [prepared] and the files it refers to as a backup zip to [out], which
     * is flushed but not closed. Files are streamed, never loaded into memory.
     * Returns the counts that were written.
     * @throws FileDeletedDuringBackupException if a file is gone by the time its
     *   entry is written: data.json, which comes first, already refers to it.
     */
    fun write(
        prepared: PreparedBackup,
        appVersion: String,
        exportedAt: Instant,
        out: OutputStream,
        onProgress: (Float) -> Unit = {},
    ): BackupCounts {
        val written = prepared.data
        val counts = prepared.counts
        val manifest = BackupManifest(
            format = BackupFormat.FORMAT_ID,
            backupVersion = BackupFormat.BACKUP_VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt.toString(),
            counts = counts.toJson(),
        )
        val files = written.filePaths.sorted()
        val steps = files.size + 2f
        var done = 0
        onProgress(0f)

        // Closing the zip ends its Deflater (native memory) but must not close the caller's stream.
        ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(out), BUFFER_SIZE)).use { zip ->
            fun entry(name: String, write: () -> Unit) {
                zip.putNextEntry(ZipEntry(name).apply { time = exportedAt.toEpochMilli() })
                write()
                zip.closeEntry()
                onProgress(++done / steps)
            }
            // Manifest and data go first: an import checks them before reading any file.
            entry(BackupFormat.MANIFEST) {
                zip.write(BackupFormat.writeJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            }
            entry(BackupFormat.DATA) {
                zip.write(BackupFormat.writeJson.encodeToString(BackupData.serializer(), written).toByteArray())
            }
            // Photos are JPEGs and don't compress; store them quickly.
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (path in files) {
                val file = File(filesDir, path)
                // Once open, a file is read to its end even if it is deleted meanwhile.
                val input = try {
                    file.inputStream()
                } catch (e: FileNotFoundException) {
                    throw if (file.exists()) e else FileDeletedDuringBackupException(path, e)
                }
                input.use { stream -> entry(path) { stream.copyTo(zip, BUFFER_SIZE) } }
            }
        }
        return counts
    }

    /** Passes writes through; `close()` only flushes. */
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() = out.flush()
    }

    private companion object {
        const val TAG = "CinexBackup"
        const val BUFFER_SIZE = 64 * 1024
    }
}
