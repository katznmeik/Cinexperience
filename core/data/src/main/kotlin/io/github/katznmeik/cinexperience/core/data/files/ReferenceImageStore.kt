package io.github.katznmeik.cinexperience.core.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * App-private storage of the layout editor's reference screenshots (spec §6.5).
 * Images live under `filesDir/reference/`; a hall stores the path relative to the
 * files dir (e.g. `reference/hall-12-<uuid>.jpg`) in `Hall.referenceImagePath`.
 *
 * The store never touches files outside its folder: paths that do not resolve
 * inside it are ignored.
 */
interface ReferenceImageStore {
    /**
     * Copies the image behind [sourceUri] (e.g. a photo picker `content://` URI)
     * into app-private storage as JPEG, downscaled so that its longer side is at
     * most [MAX_DIMENSION] px, and returns its relative path.
     * @throws IOException if the image can't be read, decoded or written.
     */
    suspend fun importImage(hallId: Long, sourceUri: String): String

    /** Decodes a stored image; `null` if it is missing or unreadable. */
    suspend fun loadImage(relativePath: String): Bitmap?

    /** Deletes a stored image; a missing file or a path outside the folder is ignored. */
    suspend fun delete(relativePath: String)

    /**
     * Deletes the stored images that are not in [referencedPaths] and were last
     * modified before [modifiedBefore] (epoch ms). The age limit protects images
     * that an open editor imported but has not saved yet. Returns how many were deleted.
     */
    suspend fun deleteOrphans(referencedPaths: Set<String>, modifiedBefore: Long): Int

    companion object {
        /** Folder under the files dir, also the prefix of every stored path. */
        const val FOLDER: String = "reference"

        /** Longer side of a stored image, in px: enough to trace a phone screenshot. */
        const val MAX_DIMENSION: Int = 2048
    }
}

internal class FileReferenceImageStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReferenceImageStore {

    private val filesDir: File get() = context.filesDir
    private val folder: File get() = File(filesDir, ReferenceImageStore.FOLDER)

    override suspend fun importImage(hallId: Long, sourceUri: String): String = withContext(ioDispatcher) {
        val bitmap = try {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(sourceUri))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (width, height) = info.size.width to info.size.height
                val longer = max(width, height)
                if (longer > ReferenceImageStore.MAX_DIMENSION) {
                    val scale = ReferenceImageStore.MAX_DIMENSION.toFloat() / longer
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) { // e.g. SecurityException (no read grant), IllegalArgumentException (bad URI)
            throw IOException("Couldn't read the image", e)
        }
        try {
            folder.mkdirs()
            val name = "hall-$hallId-${UUID.randomUUID()}.jpg"
            val target = File(folder, name)
            val temp = File(folder, "$name.tmp")
            temp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("Couldn't encode the image")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("Couldn't store the image")
            }
            "${ReferenceImageStore.FOLDER}/$name"
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun loadImage(relativePath: String): Bitmap? = withContext(ioDispatcher) {
        resolve(relativePath)?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
    }

    override suspend fun delete(relativePath: String) {
        withContext(ioDispatcher) { resolve(relativePath)?.delete() }
    }

    override suspend fun deleteOrphans(referencedPaths: Set<String>, modifiedBefore: Long): Int =
        withContext(ioDispatcher) {
            val referenced = referencedPaths.mapNotNullTo(HashSet()) { resolve(it)?.canonicalPath }
            folder.listFiles().orEmpty().count { file ->
                file.isFile && file.canonicalPath !in referenced && file.lastModified() < modifiedBefore && file.delete()
            }
        }

    /** The file of [relativePath], or `null` if it does not point inside the folder. */
    private fun resolve(relativePath: String): File? {
        val root = folder.canonicalFile
        val file = File(filesDir, relativePath).canonicalFile
        return file.takeIf { it.parentFile == root }
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }
}
