package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Pinch-zoom and pan, like `detectTransformGestures`, except that a single finger
 * only pans when [canPanWithOneFinger] says so. A seat map shown at its fitted
 * size inside a scrolling screen thus lets one-finger drags scroll the screen,
 * and only takes them over once the user has zoomed in.
 */
internal suspend fun PointerInputScope.detectZoomPan(
    canPanWithOneFinger: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        trackZoomPan(viewConfiguration.touchSlop, canPanWithOneFinger, onGesture)
    }
}

/**
 * Follows the current gesture until all pointers are up, reporting zoom and pan
 * once the movement exceeds [touchSlop]. Consumes the movements it reports.
 */
suspend fun AwaitPointerEventScope.trackZoomPan(
    touchSlop: Float,
    canPanWithOneFinger: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    alreadyPastSlop: Boolean = false,
) {
    var pastSlop = alreadyPastSlop
    var zoomSum = 1f
    var panSum = Offset.Zero
    do {
        val event: PointerEvent = awaitPointerEvent()
        if (event.changes.any { it.isConsumed }) break
        val pressed = event.changes.count { it.pressed }
        if (pressed < 2 && !canPanWithOneFinger()) continue
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        if (!pastSlop) {
            zoomSum *= zoom
            panSum += pan
            val zoomMotion = abs(1 - zoomSum) * event.calculateCentroidSize(useCurrent = false)
            if (zoomMotion > touchSlop || panSum.getDistance() > touchSlop) pastSlop = true
        }
        if (pastSlop) {
            if (zoom != 1f || pan != Offset.Zero) onGesture(event.calculateCentroid(useCurrent = false), pan, zoom)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    } while (event.changes.any { it.pressed })
}
