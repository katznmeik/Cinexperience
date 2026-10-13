package io.github.katznmeik.cinexperience.feature.seatmap.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom and pan of a seat map: `screen = content × scale + offset`. Immutable;
 * all operations return a new transform.
 */
@Immutable
data class ViewTransform(val scale: Float, val offset: Offset) {
    fun toScreen(point: Offset): Offset = Offset(point.x * scale + offset.x, point.y * scale + offset.y)

    fun toContent(point: Offset): Offset = Offset((point.x - offset.x) / scale, (point.y - offset.y) / scale)

    fun toScreen(rect: Rect): Rect = Rect(toScreen(rect.topLeft), toScreen(rect.bottomRight))

    fun panned(delta: Offset): ViewTransform = copy(offset = offset + delta)

    /** Zooms by [factor], keeping the content point under the screen point [focus] in place. */
    fun zoomed(factor: Float, focus: Offset): ViewTransform =
        ViewTransform(scale * factor, focus - (focus - offset) * factor)
}

/**
 * Zoom limits for one viewport and content: the map starts fitted to the
 * viewport's width (but never magnified beyond [maxFitScale]), can be zoomed out
 * until all content is visible and in up to [maxScale]; it can never be panned
 * further than the content's edges.
 */
@Immutable
data class ViewBounds(
    val viewport: Size,
    val content: Rect,
    val fitScale: Float,
    val minScale: Float,
    val maxScale: Float,
) {
    /** Fitted to the viewport's width, horizontally centred, top aligned. */
    fun fitted(): ViewTransform = clamp(
        ViewTransform(
            fitScale,
            Offset((viewport.width - (content.left + content.right) * fitScale) / 2f, -content.top * fitScale),
        ),
    )

    /** [transform] with its scale limited and its offset kept within the content's edges. */
    fun clamp(transform: ViewTransform): ViewTransform {
        val scale = transform.scale.coerceIn(minScale, maxScale)
        val adjusted = if (scale == transform.scale) transform else {
            // Keep the viewport centre in place when the scale had to be limited.
            val centre = Offset(viewport.width / 2f, viewport.height / 2f)
            transform.zoomed(scale / transform.scale, centre)
        }
        return ViewTransform(
            scale,
            Offset(
                clampAxis(adjusted.offset.x, viewport.width, content.left, content.right, scale),
                clampAxis(adjusted.offset.y, viewport.height, content.top, content.bottom, scale),
            ),
        )
    }

    /** Whether content can move with one finger (it's larger than the viewport). */
    fun canPan(transform: ViewTransform): Boolean =
        content.width * transform.scale > viewport.width + 0.5f || content.height * transform.scale > viewport.height + 0.5f

    /** Double tap: zoom in around [focus], or back to the fitted view when already zoomed in. */
    fun toggleZoom(transform: ViewTransform, focus: Offset): ViewTransform? =
        if (transform.scale < fitScale * DOUBLE_TAP_THRESHOLD) {
            clamp(transform.zoomed(fitScale * DOUBLE_TAP_ZOOM / transform.scale, focus))
        } else {
            null
        }

    companion object {
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val DOUBLE_TAP_THRESHOLD = 1.6f

        /**
         * @param maxFitScale the fitted view never magnifies more than this (tiny halls).
         * @param maxZoomScale lower bound of the maximum zoom, e.g. "a seat is ~48 dp wide".
         */
        fun of(viewport: Size, content: Rect, maxFitScale: Float, maxZoomScale: Float): ViewBounds {
            val safeWidth = max(content.width, 1f)
            val safeHeight = max(content.height, 1f)
            val fit = min(viewport.width / safeWidth, maxFitScale).coerceAtLeast(MIN_SCALE)
            val all = min(fit, viewport.height / safeHeight).coerceAtLeast(MIN_SCALE)
            return ViewBounds(viewport, content, fit, all, max(fit * 4f, maxZoomScale))
        }

        private const val MIN_SCALE = 0.01f

        /**
         * Allowed offset on one axis: content smaller than the viewport stays fully
         * inside it; larger content may not show empty space beyond its edges.
         */
        private fun clampAxis(offset: Float, viewport: Float, start: Float, end: Float, scale: Float): Float {
            val a = -start * scale // content start at the viewport start
            val b = viewport - end * scale // content end at the viewport end
            return offset.coerceIn(min(a, b), max(a, b))
        }
    }
}
