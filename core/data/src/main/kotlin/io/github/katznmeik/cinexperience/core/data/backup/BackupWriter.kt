package io.github.katznmeik.cinexperience.core.data.backup

import android.util.Log
import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import java.io.BufferedOutputStream
import java.io.File
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

/** Writes backup zips (export and pre-import safety copies). */
internal class BackupWriter(private val filesDir: File) {

    /**
     * Writes [data] and the files it refers to as a backup zip to [out], which is
     * flushed but not closed. Files are streamed, never loaded into memory.
     *
     * A reference to a file that is missing on disk (or has an unsafe path) is
     * left out of the backup (written as `null`) and logged, so one lost photo
     * can't block every export; the app shows such a photo as missing anyway.
     * Returns the counts that were written.
     */
    fun write(
        data: BackupData,
        appVersion: String,
        exportedAt: Instant,
        out: OutputStream,
        onProgress: (Float) -> Unit = {},
    ): BackupCounts {
        val available = data.filePaths.filterTo(HashSet()) { path ->
            BackupFormat.isFilePath(path) && File(filesDir, path).isFile
        }
        val missing = data.filePaths - available
        if (missing.isNotEmpty()) Log.w(TAG, "Backup leaves out ${missing.size} missing file(s): $missing")
        val written = data.copy(
            halls = data.halls.map { if (it.referenceImagePath in missing) it.copy(referenceImagePath = null) else it },
            visits = data.visits.map { if (it.photoPath in missing) it.copy(photoPath = null) else it },
        )
        val counts = written.counts()
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

        val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER_SIZE))
        fun entry(name: String, write: () -> Unit) {
            zip.putNextEntry(ZipEntry(name).apply { time = exportedAt.toEpochMilli() })
            write()
            zip.closeEntry()
            onProgress(++done / steps)
        }
        // The manifest goes first, so an import can reject a wrong file before unpacking the rest.
        entry(BackupFormat.MANIFEST) {
            zip.write(BackupFormat.writeJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
        }
        entry(BackupFormat.DATA) {
            zip.write(BackupFormat.writeJson.encodeToString(BackupData.serializer(), written).toByteArray())
        }
        // Photos are JPEGs and don't compress; store them quickly.
        zip.setLevel(Deflater.NO_COMPRESSION)
        for (path in files) {
            entry(path) { File(filesDir, path).inputStream().use { it.copyTo(zip, BUFFER_SIZE) } }
        }
        zip.finish()
        zip.flush()
        return counts
    }

    private companion object {
        const val TAG = "CinexBackup"
        const val BUFFER_SIZE = 64 * 1024
    }
}
