package io.github.katznmeik.cinexperience.core.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * App-private storage of visit photos (spec §4.4). Photos live under
 * `filesDir/photos/`; a visit stores the path relative to the files dir (e.g.
 * `photos/visit-<uuid>.jpg`) in `Visit.photoPath`. The backup export (M6) will
 * include this folder; Android Auto Backup leaves it out (database and
 * preferences only).
 *
 * Camera captures are written by the camera app to a temporary file under
 * `cacheDir/camera/`, shared through a `FileProvider` URI, and then imported like
 * a gallery photo.
 *
 * The store never touches files outside its folders: paths that do not resolve
 * inside them are ignored.
 */
interface VisitPhotoStore {
    /**
     * Copies the image behind [sourceUri] (a photo picker or capture `content://`
     * URI) into app-private storage as JPEG, rotated upright according to its EXIF
     * orientation and downscaled so that its longer side is at most
     * [MAX_DIMENSION] px, and returns its relative path.
     * @throws IOException if the image can't be read, decoded or written.
     */
    suspend fun importPhoto(sourceUri: String): String

    /**
     * Creates an empty temporary file for the camera app and returns its
     * `content://` URI, to be passed to `ActivityResultContracts.TakePicture`.
     * Import it with [importPhoto] and then remove it with [deleteCapture].
     * @throws IOException if the file can't be created.
     */
    suspend fun createCaptureUri(): String

    /** Deletes a temporary capture file from [createCaptureUri]; anything else is ignored. */
    suspend fun deleteCapture(captureUri: String)

    /**
     * Decodes a stored photo, subsampled so that its longer side is not much
     * larger than [maxDimension] px; `null` if it is missing or unreadable.
     */
    suspend fun loadPhoto(relativePath: String, maxDimension: Int = MAX_DIMENSION): Bitmap?

    /** Deletes a stored photo; a missing file or a path outside the folder is ignored. */
    suspend fun delete(relativePath: String)

    /**
     * Deletes the stored photos that are not in [referencedPaths], and leftover
     * capture files, that were last modified before [modifiedBefore] (epoch ms).
     * The age limit protects photos that an open visit form added but has not
     * saved yet. Returns how many files were deleted.
     */
    suspend fun deleteOrphans(referencedPaths: Set<String>, modifiedBefore: Long): Int

    companion object {
        /** Folder under the files dir, also the prefix of every stored path. */
        const val FOLDER: String = "photos"

        /** Longer side of a stored photo, in px. */
        const val MAX_DIMENSION: Int = 2048
    }
}

internal class FileVisitPhotoStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VisitPhotoStore {

    private val filesDir: File get() = context.filesDir
    private val folder: File get() = File(filesDir, VisitPhotoStore.FOLDER)
    private val captureFolder: File get() = File(context.cacheDir, CAPTURE_FOLDER)
    private val authority: String get() = "${context.packageName}$AUTHORITY_SUFFIX"

    override suspend fun importPhoto(sourceUri: String): String = withContext(ioDispatcher) {
        val bitmap = try {
            decodeUpright(Uri.parse(sourceUri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) { // e.g. SecurityException (no read grant), IllegalArgumentException (bad URI)
            throw IOException("Couldn't read the photo", e)
        } catch (e: OutOfMemoryError) {
            throw IOException("The photo is too large", e)
        }
        try {
            folder.mkdirs()
            val name = "visit-${UUID.randomUUID()}.jpg"
            val target = File(folder, name)
            val temp = File(folder, "$name.tmp")
            try {
                temp.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("Couldn't encode the photo")
                }
                if (!temp.renameTo(target)) throw IOException("Couldn't store the photo")
            } finally {
                temp.delete()
            }
            "${VisitPhotoStore.FOLDER}/$name"
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes [uri] downscaled to [VisitPhotoStore.MAX_DIMENSION] and turned upright. */
    private fun decodeUpright(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), VisitPhotoStore.MAX_DIMENSION)
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("Couldn't decode the photo")
        val orientation = readOrientation { open(uri) }
        val longer = max(decoded.width, decoded.height)
        val scale = if (longer > VisitPhotoStore.MAX_DIMENSION) VisitPhotoStore.MAX_DIMENSION.toFloat() / longer else 1f
        val matrix = orientationMatrix(orientation).apply { preScale(scale, scale) }
        if (matrix.isIdentity) return decoded
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        // Rounding may leave the longer side one pixel over the limit; trim it.
        val result = if (max(transformed.width, transformed.height) > VisitPhotoStore.MAX_DIMENSION) {
            val f = VisitPhotoStore.MAX_DIMENSION.toFloat() / max(transformed.width, transformed.height)
            Bitmap.createScaledBitmap(
                transformed,
                (transformed.width * f).roundToInt().coerceAtLeast(1),
                (transformed.height * f).roundToInt().coerceAtLeast(1),
                true,
            ).also { if (it !== transformed) transformed.recycle() }
        } else {
            transformed
        }
        if (result !== decoded) decoded.recycle()
        return result
    }

    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the photo")

    override suspend fun createCaptureUri(): String = withContext(ioDispatcher) {
        try {
            captureFolder.mkdirs()
            val file = File(captureFolder, "capture-${UUID.randomUUID()}.jpg")
            if (!file.createNewFile()) throw IOException("Couldn't create the capture file")
            FileProvider.getUriForFile(context, authority, file).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) { // IllegalArgumentException: provider not configured
            throw IOException("Couldn't prepare the camera", e)
        }
    }

    override suspend fun deleteCapture(captureUri: String) {
        withContext(ioDispatcher) {
            val uri = Uri.parse(captureUri)
            if (uri.scheme != "content" || uri.authority != authority) return@withContext
            val name = uri.lastPathSegment ?: return@withContext
            val root = captureFolder.canonicalFile
            File(root, name).canonicalFile.takeIf { it.parentFile == root }?.delete()
        }
    }

    override suspend fun loadPhoto(relativePath: String, maxDimension: Int): Bitmap? = withContext(ioDispatcher) {
        val file = resolve(relativePath)?.takeIf { it.isFile } ?: return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), maxDimension)
            }
            BitmapFactory.decodeFile(file.path, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    override suspend fun delete(relativePath: String) {
        withContext(ioDispatcher) { resolve(relativePath)?.delete() }
    }

    override suspend fun deleteOrphans(referencedPaths: Set<String>, modifiedBefore: Long): Int =
        withContext(ioDispatcher) {
            val referenced = referencedPaths.mapNotNullTo(HashSet()) { resolve(it)?.canonicalPath }
            val photos = folder.listFiles().orEmpty().count { file ->
                file.isFile && file.canonicalPath !in referenced && file.lastModified() < modifiedBefore && file.delete()
            }
            val captures = captureFolder.listFiles().orEmpty().count { file ->
                file.isFile && file.lastModified() < modifiedBefore && file.delete()
            }
            photos + captures
        }

    /** The file of [relativePath], or `null` if it does not point inside the folder. */
    private fun resolve(relativePath: String): File? {
        val root = folder.canonicalFile
        val file = File(filesDir, relativePath).canonicalFile
        return file.takeIf { it.parentFile == root }
    }

    companion object {
        private const val JPEG_QUALITY = 90

        /** Capture folder under the cache dir; must match `res/xml/visit_photo_paths.xml`. */
        internal const val CAPTURE_FOLDER = "camera"

        /** Appended to the package name; must match the provider in the manifest. */
        internal const val AUTHORITY_SUFFIX = ".photos"

        /** The largest power of two that keeps the longer side at or above [target] (1 if it's already smaller). */
        internal fun sampleSize(longer: Int, target: Int): Int {
            var sample = 1
            while (longer / (sample * 2) >= target) sample *= 2
            return sample
        }

        /**
         * The EXIF orientation of the image from [open]. Missing or unreadable EXIF
         * data (e.g. a PNG, a damaged header) never fails an import: it counts as
         * [ExifInterface.ORIENTATION_NORMAL].
         */
        internal fun readOrientation(open: () -> InputStream): Int = try {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        /** The transform that turns an image with this EXIF [orientation] upright. */
        internal fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                else -> Unit
            }
        }
    }
}
