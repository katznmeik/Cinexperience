package io.github.katznmeik.cinexperience.core.testing

import android.graphics.Bitmap
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import java.io.IOException

/**
 * In-memory [ReferenceImageStore]: "files" are just paths in [files]. Imports
 * return `reference/hall-<id>-<n>.jpg`. Set [importFailure] to make imports fail,
 * and [bitmap] to what [loadImage] returns for an existing path.
 */
class FakeReferenceImageStore(initialFiles: Set<String> = emptySet()) : ReferenceImageStore {

    /** The stored paths. */
    val files: MutableSet<String> = initialFiles.toMutableSet()

    /** Every path passed to [delete], in order. */
    val deleted: MutableList<String> = mutableListOf()

    /** Source URIs passed to [importImage], in order. */
    val importedSources: MutableList<String> = mutableListOf()

    var importFailure: IOException? = null

    var bitmap: Bitmap? = null

    private var counter = 0

    override suspend fun importImage(hallId: Long, sourceUri: String): String {
        importFailure?.let { throw it }
        importedSources += sourceUri
        val path = "${ReferenceImageStore.FOLDER}/hall-$hallId-${++counter}.jpg"
        files += path
        return path
    }

    override suspend fun loadImage(relativePath: String): Bitmap? = if (relativePath in files) bitmap else null

    override suspend fun delete(relativePath: String) {
        deleted += relativePath
        files -= relativePath
    }

    override suspend fun deleteOrphans(referencedPaths: Set<String>, modifiedBefore: Long): Int {
        val orphans = files - referencedPaths
        orphans.forEach { delete(it) }
        return orphans.size
    }
}
