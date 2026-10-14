package io.github.katznmeik.cinexperience.core.testing

import android.graphics.Bitmap
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import java.io.IOException

/**
 * In-memory [VisitPhotoStore]: "files" are just paths in [files]. Imports return
 * `photos/visit-<n>.jpg`, captures `content://test.photos/camera/capture-<n>.jpg`.
 * Set [importFailure] / [captureFailure] to make those calls fail, and [bitmap] to
 * what [loadPhoto] returns for an existing path.
 */
class FakeVisitPhotoStore(initialFiles: Set<String> = emptySet()) : VisitPhotoStore {

    /** The stored photo paths. */
    val files: MutableSet<String> = initialFiles.toMutableSet()

    /** Capture URIs that exist (created and not yet deleted). */
    val captures: MutableSet<String> = mutableSetOf()

    /** Every photo path passed to [delete], in order. */
    val deleted: MutableList<String> = mutableListOf()

    /** Source URIs passed to [importPhoto], in order. */
    val importedSources: MutableList<String> = mutableListOf()

    var importFailure: IOException? = null

    var captureFailure: IOException? = null

    var bitmap: Bitmap? = null

    private var counter = 0

    override suspend fun importPhoto(sourceUri: String): String {
        importFailure?.let { throw it }
        importedSources += sourceUri
        val path = "${VisitPhotoStore.FOLDER}/visit-${++counter}.jpg"
        files += path
        return path
    }

    override suspend fun createCaptureUri(): String {
        captureFailure?.let { throw it }
        val uri = "content://test.photos/camera/capture-${++counter}.jpg"
        captures += uri
        return uri
    }

    override suspend fun deleteCapture(captureUri: String) {
        captures -= captureUri
    }

    override suspend fun loadPhoto(relativePath: String, maxDimension: Int): Bitmap? =
        if (relativePath in files) bitmap else null

    override suspend fun delete(relativePath: String) {
        deleted += relativePath
        files -= relativePath
    }

    override suspend fun deleteOrphans(referencedPaths: Set<String>, modifiedBefore: Long): Int {
        val orphans = files - referencedPaths
        orphans.forEach { delete(it) }
        return orphans.size + captures.size.also { captures.clear() }
    }
}
