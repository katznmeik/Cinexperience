package io.github.katznmeik.cinexperience.core.ui.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException

/**
 * The image at [path], loaded with [load] (e.g. from an app-private file store) and
 * loaded again when [path] changes. `null` until the first image has loaded, without
 * a path, and when [load] finds nothing or fails.
 */
@Composable
fun rememberLoadedImage(path: String?, load: suspend (path: String) -> Bitmap?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            try {
                load(it)?.asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
