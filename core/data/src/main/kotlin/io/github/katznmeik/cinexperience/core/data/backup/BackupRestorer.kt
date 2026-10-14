package io.github.katznmeik.cinexperience.core.data.backup

import android.util.Log
import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Points in [BackupRestorer.replace] where tests inject failures. */
internal enum class ImportStep {
    /** After each file was moved into place, before the database is touched. */
    FILE_PLACED,

    /** Inside the transaction, after all rows were replaced, before the commit. */
    ROWS_REPLACED,

    /** After the commit, before files of the old data are deleted. */
    COMMITTED,
}

/**
 * Replaces all data with a staged backup, so that it either fully happens or
 * leaves the old data and files untouched:
 *
 * 1. **Add files.** Each staged file is moved into `filesDir/photos|reference/`
 *    under its own name, which is never an existing file's name: if a file of that
 *    name exists with the same bytes it is reused; with different bytes the
 *    imported file gets a fresh name and the data is changed to point to it. So no
 *    existing file is overwritten, and the old data stays complete.
 * 2. **Replace rows** in one Room transaction (clear every table, insert every
 *    row with its original id), after checking that the data still equals the
 *    snapshot the safety copy was made from.
 * 3. If 1 or 2 fails, the files added in 1 are deleted again and the transaction is
 *    rolled back: the old data and files are intact.
 * 4. After the commit, files that only the old data referenced are deleted.
 *
 * Process death: SQLite rolls an unfinished transaction back. Files added before
 * the commit (or old files not yet deleted after it) are unreferenced and are
 * removed by the startup sweep of orphan files.
 */
internal class BackupRestorer(
    private val db: CinexDatabase,
    private val filesDir: File,
    private val now: () -> Long,
    private val failpoint: (ImportStep) -> Unit = {},
) {
    /**
     * Replaces everything with [staged]. [expected] is the current data as read
     * for the safety copy; if the database changed since, nothing is replaced.
     * Returns the data as imported (file paths possibly renamed).
     */
    suspend fun replace(staged: StagedBackup, expected: BackupData, onProgress: (Float) -> Unit = {}): BackupData {
        val added = ArrayList<File>()
        val renamed = HashMap<String, String>()
        val oldData: BackupData
        val newData: BackupData
        try {
            var done = 0
            for ((path, stagedFile) in staged.files) {
                val target = File(filesDir, path)
                if (target.exists() && target.isFile && sameContent(target, stagedFile.file)) {
                    // Already there (e.g. re-importing a backup of this device): reuse it. It may be
                    // an old unreferenced file, so refresh its date to keep the orphan sweep away.
                    target.setLastModified(now())
                } else {
                    val finalPath = if (target.exists()) freePath(path).also { renamed[path] = it } else path
                    val finalTarget = File(filesDir, finalPath)
                    place(stagedFile.file, finalTarget)
                    added += finalTarget
                    failpoint(ImportStep.FILE_PLACED)
                }
                done++
                onProgress(done.toFloat() / staged.files.size * FILES_SHARE)
            }
            newData = staged.data.withFilePaths(renamed)
            oldData = db.withTransaction {
                val current = db.readBackupDataInTransaction()
                if (current != expected) {
                    throw BackupException(
                        BackupError.DATA_CHANGED,
                        "Your data changed while importing. Nothing was replaced; please try again.",
                    )
                }
                replaceRows(newData)
                failpoint(ImportStep.ROWS_REPLACED)
                current
            }
        } catch (e: Throwable) {
            // Not committed: remove what was added, the old files were never touched.
            added.forEach { it.delete() }
            throw e
        }
        onProgress(1f)
        try {
            failpoint(ImportStep.COMMITTED)
            for (path in oldData.filePaths - newData.filePaths) {
                if (BackupFormat.isFilePath(path)) File(filesDir, path).delete()
            }
        } catch (e: Exception) {
            // The import is complete; leftovers are removed by the startup sweep.
            Log.w(TAG, "Deleting files of the replaced data failed", e)
        }
        return newData
    }

    private suspend fun replaceRows(data: BackupData) {
        val dao = db.backupDao()
        dao.clearSeatOverrides()
        dao.clearVisits()
        dao.clearHalls()
        dao.clearCinemas()
        dao.clearSeedSnapshots()
        dao.clearMetadata()
        dao.insertCinemas(data.cinemas.map { it.toEntity() })
        dao.insertHalls(data.halls.map { it.toEntity() })
        dao.insertVisits(data.visits.map { it.toEntity() })
        dao.insertSeatOverrides(data.seatOverrides.map { it.toEntity() })
        dao.insertSeedSnapshots(data.seedSnapshots.map { it.toEntity() })
        dao.insertMetadata(data.metadata.map { it.toEntity() })
    }

    /** Moves [source] to the not yet existing [target] (copying if a rename isn't possible). */
    private fun place(source: File, target: File) {
        val folder = target.parentFile ?: throw IOException("No folder for $target")
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Couldn't create $folder")
        if (!source.renameTo(target)) {
            val temp = File(folder, "${target.name}.import")
            try {
                source.inputStream().use { input ->
                    FileOutputStream(temp).use { out ->
                        input.copyTo(out, BUFFER_SIZE)
                        out.flush()
                        out.fd.sync()
                    }
                }
                if (!temp.renameTo(target)) throw IOException("Couldn't move $temp")
            } finally {
                temp.delete()
            }
        }
        // A fresh date, so the orphan sweep (which spares recent files) never removes
        // it while the transaction is still running.
        target.setLastModified(now())
    }

    /** A path in the same folder as [path] that doesn't exist yet. */
    private fun freePath(path: String): String {
        val folder = path.substringBefore('/')
        val extension = path.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 && '/' !in it }
        while (true) {
            val candidate = "$folder/import-${UUID.randomUUID()}" + (extension?.let { ".$it" } ?: "")
            if (!File(filesDir, candidate).exists()) return candidate
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        val bufferA = ByteArray(BUFFER_SIZE)
        val bufferB = ByteArray(BUFFER_SIZE)
        a.inputStream().use { ia ->
            b.inputStream().use { ib ->
                while (true) {
                    val readA = ia.readNBytes(bufferA, 0, BUFFER_SIZE)
                    val readB = ib.readNBytes(bufferB, 0, BUFFER_SIZE)
                    if (!java.util.Arrays.equals(bufferA, 0, readA, bufferB, 0, readB)) return false
                    if (readA < BUFFER_SIZE) return true
                }
            }
        }
    }

    private companion object {
        const val TAG = "CinexBackup"
        const val BUFFER_SIZE = 64 * 1024

        /** Share of the progress for moving files; the transaction is quick. */
        const val FILES_SHARE = 0.9f
    }
}
