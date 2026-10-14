package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.seatmapui.MAX_FIT_SCALE
import io.github.katznmeik.cinexperience.core.seatmapui.MAX_ZOOM_SEAT_SIZE
import io.github.katznmeik.cinexperience.core.seatmapui.NumberedSeat
import io.github.katznmeik.cinexperience.core.seatmapui.SeatGlyphs
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapGeometry
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapMetrics
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapViewState
import io.github.katznmeik.cinexperience.core.seatmapui.SeatNumberTexts
import io.github.katznmeik.cinexperience.core.seatmapui.SeatVisual
import io.github.katznmeik.cinexperience.core.seatmapui.ViewBounds
import io.github.katznmeik.cinexperience.core.seatmapui.ViewTransform
import io.github.katznmeik.cinexperience.core.seatmapui.drawGlyph
import io.github.katznmeik.cinexperience.core.seatmapui.glyphStroke
import io.github.katznmeik.cinexperience.core.seatmapui.innerSquare
import io.github.katznmeik.cinexperience.core.seatmapui.measureRowLabels
import io.github.katznmeik.cinexperience.core.seatmapui.rowLabelWidth
import io.github.katznmeik.cinexperience.core.seatmapui.seatNumberText
import io.github.katznmeik.cinexperience.core.seatmapui.seatNumbersMinScale
import io.github.katznmeik.cinexperience.core.seatmapui.seatRoundRect
import io.github.katznmeik.cinexperience.core.seatmapui.strokeGlyph
import io.github.katznmeik.cinexperience.core.seatmapui.trackZoomPan
import io.github.katznmeik.cinexperience.core.ui.theme.CinexColors
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the editor canvas reports; implemented by the screen with the ViewModel. */
internal interface EditorCanvasActions {
    fun onBeginGesture()
    fun onEndGesture()
    fun onTapCell(row: Int, column: Int)
    fun onPaintCells(cells: List<CellPosition>)
    fun onSelectRow(row: Int)

    /** A touch on the screen bar (or its label): selects the screen. */
    fun onSelectScreen()

    /** A tap on the canvas outside the grid, the row labels and the screen. */
    fun onTapOutside()
    fun onScreenWidth(width: Float)
    fun onScreenDistance(distance: Float)

    /** The screen's sideways position from dragging its bar (seat widths, not yet snapped). */
    fun onScreenOffset(offset: Float)
    fun onReferenceAlignment(alignment: ReferenceAlignment)
}

/** The direction a drag on the screen bar is locked to. */
internal enum class DragAxis {
    /** Sideways: moves the screen left or right (its offset). */
    HORIZONTAL,

    /** Up or down: changes the screen's distance to row 0. */
    VERTICAL,
}

/**
 * The axis a drag on the screen bar locks to, from its [total] movement so far:
 * `null` while it is within the touch [slop], then the direction of the larger
 * component (sideways on a tie). Once locked, the drag keeps its axis until the
 * finger lifts, so a slightly diagonal drag never changes both the position and
 * the distance.
 */
internal fun dragAxis(total: Offset, slop: Float): DragAxis? = when {
    total.getDistance() <= slop -> null
    abs(total.x) >= abs(total.y) -> DragAxis.HORIZONTAL
    else -> DragAxis.VERTICAL
}

/**
 * Sizes of the editor drawing: the seat map's proportions (big cells, narrow
 * gaps, so the seat numbers fit) and labels on the left only. The screen's label
 * and handles are drawn at a fixed size on screen, so their room is not part of
 * the content but kept by the view (see [editorViewBounds]).
 */
internal fun editorMetrics(density: Density, labelWidth: Float): SeatMapMetrics = with(density) {
    SeatMapMetrics(
        seatSize = 16.dp.toPx(),
        seatGap = 2.dp.toPx(),
        rowGap = 3.dp.toPx(),
        labelWidth = labelWidth,
        labelGap = 4.dp.toPx(),
        screenTopSpace = 0f,
        minScreenGap = 0f,
        padding = 4.dp.toPx(),
        rightLabels = false,
    )
}

/**
 * The editor's zoom limits for a canvas of [viewport] size, keeping room on screen
 * for what is drawn at a fixed size, however small a wide hall is drawn:
 * [labelHeight] px above the drawing for the screen's label ([SCREEN_LABEL_HEIGHT]),
 * and [handleRoom] px beside the screen's ends for its handles ([HANDLE_ROOM]).
 *
 * The handle room is added to the content's sides in content units for the fitted
 * scale, which is solved for it (the room shrinks the scale, the scale sets the
 * room): exactly enough when fitted, more when zoomed in.
 */
internal fun editorViewBounds(
    viewport: Size,
    geometry: SeatMapGeometry,
    maxZoomScale: Float,
    labelHeight: Float,
    handleRoom: Float,
): ViewBounds {
    val content = geometry.contentBounds
    val width = viewport.width
    val left = geometry.screenLeft
    val right = geometry.screenRight
    // The largest scale s at which the content and the screen with handleRoom px on both
    // sides fit the width: every pair of a left and a right edge, e.g. (right − left) × s + 2 × room ≤ width.
    val scale = minOf(
        MAX_FIT_SCALE,
        width / max(content.width, 1f),
        (width - handleRoom) / max(right - content.left, 1f),
        (width - handleRoom) / max(content.right - left, 1f),
        (width - 2f * handleRoom) / max(right - left, 1f),
    ).coerceAtLeast(MIN_FIT_SCALE)
    val room = handleRoom / scale
    val withHandles = Rect(min(content.left, left - room), content.top, max(content.right, right + room), content.bottom)
    return ViewBounds.of(viewport, withHandles, MAX_FIT_SCALE, maxZoomScale, topInset = labelHeight)
}

/** Lower limit of the fitted scale in [editorViewBounds] (a degenerate, tiny canvas). */
private const val MIN_FIT_SCALE = 0.01f

/** The selected screen's width handles: drawn this big, touched within [HANDLE_TOUCH_RADIUS]. */
private val HANDLE_RADIUS = 11.dp
private val HANDLE_RING = 2.5.dp
private val HANDLE_TOUCH_RADIUS = 24.dp

/** Room on screen beside each end of the screen bar: the handle with its ring, and a margin. */
internal val HANDLE_ROOM = HANDLE_RADIUS + HANDLE_RING + 4.dp

/**
 * Room for the label above the screen bar, on screen: the label (up to 33 dp
 * above the bar's line) is drawn within it, and its touch area reaches down to the bar.
 */
internal val SCREEN_LABEL_HEIGHT = 34.dp

/** A little room below the grid, so the last row doesn't touch the canvas edge. */
private val BOTTOM_SPACE = 6.dp

/**
 * Opacity of the muted gap dashes and aisle hatching: at least 3:1 against the
 * canvas (non-text contrast), still quieter than the seats. Checked by EditorContrastTest.
 */
internal const val EDITOR_MARK_ALPHA = 0.65f

/** Fill of the editor's seats: translucent, so a reference screenshot shows through. */
internal const val EDITOR_SEAT_FILL_ALPHA = 0.12f

/**
 * Default placement of the reference screenshot: as wide as the whole drawing,
 * top-aligned with it (in grid units, see [ReferenceAlignment]).
 */
internal fun defaultReferenceAlignment(geometry: SeatMapGeometry): ReferenceAlignment {
    val unit = geometry.metrics.pitchX
    val bounds = geometry.contentBounds
    return ReferenceAlignment(bounds.left / unit, bounds.top / unit, bounds.width / unit)
}

/** The screenshot's rectangle in content coordinates. */
internal fun referenceRect(alignment: ReferenceAlignment, geometry: SeatMapGeometry, image: ImageBitmap): Rect {
    val unit = geometry.metrics.pitchX
    val width = alignment.width * unit
    val height = width * image.height / max(image.width, 1)
    return Rect(alignment.left * unit, alignment.top * unit, alignment.left * unit + width, alignment.top * unit + height)
}

/**
 * Moves and scales [alignment] by a pan / pinch on screen: [pan] in screen px,
 * [zoom] about the screen point [centroid], with the view [transform].
 */
internal fun alignedBy(
    alignment: ReferenceAlignment,
    geometry: SeatMapGeometry,
    transform: ViewTransform,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
): ReferenceAlignment {
    val unit = geometry.metrics.pitchX
    val contentWidth = geometry.contentBounds.width / unit
    val width = (alignment.width * zoom).coerceIn(contentWidth * 0.2f, contentWidth * 10f)
    val factor = width / alignment.width
    val focus = transform.toContent(centroid) / unit
    val panUnits = pan / (transform.scale * unit)
    return ReferenceAlignment(
        left = focus.x - (focus.x - alignment.left) * factor + panUnits.x,
        top = focus.y - (focus.y - alignment.top) * factor + panUnits.y,
        width = width,
    )
}

/** What touches on the editor canvas do. */
internal enum class CanvasMode {
    /** Paint cells, select rows, select and drag the screen; two fingers zoom. */
    EDIT,

    /** One or two fingers move and scale the reference screenshot. */
    ALIGN_REFERENCE,

    /** Reference mode while the screenshot isn't there (yet): only zoom and pan, never paint. */
    VIEW_ONLY,
}

internal fun canvasMode(referenceMode: Boolean, hasReferenceImage: Boolean): CanvasMode = when {
    !referenceMode -> CanvasMode.EDIT
    hasReferenceImage -> CanvasMode.ALIGN_REFERENCE
    else -> CanvasMode.VIEW_ONLY
}

/** Where a touch on the editor canvas lands. */
internal sealed interface CanvasHit {
    /** A width handle of the selected screen. */
    data class WidthHandle(val right: Boolean) : CanvasHit

    /** The screen bar or the label above it: selects the screen, and dragging it moves the screen. */
    data object ScreenBar : CanvasHit
    data class Cell(val position: CellPosition) : CanvasHit
    data class RowLabel(val row: Int) : CanvasHit
    data object Empty : CanvasHit
}

/**
 * Where the screen's label is drawn, in screen px: its backdrop around a label of
 * [size] whose bottom is [gap] above the bar's line at [barY]. Centred over the
 * bar ([barCentreX]), but kept inside a canvas [canvasWidth] wide when the bar is
 * zoomed partly out of view. [dp] is px per dp. Drawing and [hitTest] both use it,
 * so the label is touched where it is seen.
 */
internal fun screenLabelRect(barCentreX: Float, barY: Float, size: Size, gap: Float, canvasWidth: Float, dp: Float): Rect {
    val margin = 6f * dp
    val centred = barCentreX - size.width / 2f
    val left = if (canvasWidth - 2f * margin < size.width) centred else centred.coerceIn(margin, canvasWidth - margin - size.width)
    val top = barY - gap - size.height
    return Rect(left, top, left + size.width, top + size.height).inflate(LABEL_BACKDROP_MARGIN * dp)
}

/** Margin of the label's backdrop around its text, in dp. */
private const val LABEL_BACKDROP_MARGIN = 3f

/**
 * Hit-test of a screen point: above the grid, the selected screen's width handles
 * first (a finger-sized circle; an unselected screen has no handles), then the
 * screen bar and its label ([label]: where it is drawn, from [screenLabelRect];
 * its touch area reaches down to the bar); then cells and row labels. The screen
 * controls never reach into row 0, so a wide hall or a small screen distance
 * can't turn a paint stroke into a screen drag.
 *
 * The middle of the bar ([handleTouchRadius] × 2/3 wide) always moves the bar,
 * also on a short screen whose handles' touch circles would cover all of it; its
 * handles are then touched on their outer sides.
 */
internal fun hitTest(
    point: Offset,
    geometry: SeatMapGeometry,
    transform: ViewTransform,
    handleTouchRadius: Float,
    label: Rect,
    screenSelected: Boolean,
): CanvasHit {
    // Top of row 0's band (cells own half of the row gap above them, see cellAt).
    val gridTop = transform.toScreen(Offset(0f, -geometry.metrics.rowGap / 2f)).y
    if (point.y < gridTop) {
        val left = transform.toScreen(Offset(geometry.screenLeft, geometry.screenY))
        val right = transform.toScreen(Offset(geometry.screenRight, geometry.screenY))
        if (screenSelected) {
            val toRight = (point - right).getDistance()
            val toLeft = (point - left).getDistance()
            val inMiddle = abs(point.x - (left.x + right.x) / 2f) < handleTouchRadius / 3f
            if (!inMiddle && min(toLeft, toRight) <= handleTouchRadius) return CanvasHit.WidthHandle(right = toRight <= toLeft)
        }
        val reach = handleTouchRadius * 0.6f
        val onBar = point.x in (left.x - reach)..(right.x + reach) && abs(point.y - left.y) <= reach
        // The label may be wider than a short bar, and is kept inside the canvas when zoomed.
        val onLabel = point.x in label.left..label.right && point.y in label.top..left.y
        if (onBar || onLabel) return CanvasHit.ScreenBar
    }
    val content = transform.toContent(point)
    geometry.cellAt(content)?.let { return CanvasHit.Cell(it) }
    geometry.rowLabelAt(content)?.let { return CanvasHit.RowLabel(it) }
    return CanvasHit.Empty
}

/** The canvas's hint line under the grid. */
internal fun canvasHint(referenceMode: Boolean, hasReferenceImage: Boolean, referenceOpacity: Float, screenSelected: Boolean): String = when {
    referenceMode && hasReferenceImage -> "Reference screenshot · ${(referenceOpacity * 100).roundToInt()} % opacity · drag to align"
    referenceMode -> "Add a screenshot below to trace it"
    screenSelected -> "Drag the green handles to resize · drag the bar to move"
    hasReferenceImage -> "Reference screenshot · ${(referenceOpacity * 100).roundToInt()} % opacity"
    else -> "Tap or drag to paint · tap the screen to adjust it"
}

/**
 * The layout editor's canvas (spec §6.5): the grid with seats as outlined
 * squares showing their seat numbers, gaps dashed and aisles hatched, the screen
 * as a straight bar, and the reference screenshot behind everything.
 *
 * Gestures: tap a cell to paint it, drag across cells to paint a stroke (one undo
 * step), tap a row label to select the row, tap the screen bar to select the
 * screen (it turns green and shows its handles), drag the selected screen's
 * handles to change its width, drag the bar up or down to change its distance
 * or sideways to move it (the drag locks to the direction it starts in, see
 * [dragAxis]), and use two fingers (or one finger outside the grid) to zoom and
 * pan. In reference mode, one or two fingers move and scale the screenshot instead.
 */
@Composable
internal fun EditorCanvas(
    draft: LayoutDraft,
    labels: LayoutLabels,
    selectedRow: Int?,
    referenceMode: Boolean,
    referenceImage: ImageBitmap?,
    referenceOpacity: Float,
    referenceAlignment: ReferenceAlignment?,
    actions: EditorCanvasActions,
    viewState: SeatMapViewState,
    modifier: Modifier = Modifier,
    screenSelected: Boolean = false,
    minHeight: Dp = 212.dp,
    maxHeight: Dp = 480.dp,
) {
    val density = LocalDensity.current
    val colors = CinexTheme.colors
    val textMeasurer = rememberTextMeasurer()
    // Text drawn in the grid (row labels, seat numbers, screen label) is sized in dp, not
    // sp: it has to fit the rows, and it grows with zoom instead of with the system font size.
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = with(density) { 11.dp.toSp() },
        lineHeight = with(density) { 13.dp.toSp() },
        color = colors.textMuted,
    )
    val selectedLabelStyle = labelStyle.copy(color = colors.onPrimary, fontWeight = FontWeight.SemiBold)
    val screenLabelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = with(density) { 11.dp.toSp() },
        lineHeight = with(density) { 14.dp.toSp() },
        color = colors.text.copy(alpha = 0.8f),
    )
    val numberStyle = SeatNumberTexts.style(MaterialTheme.typography.labelSmall)

    // Measured once per row labels (and style), not on every paint stroke.
    val rowLabelTexts = remember(textMeasurer, labels.rowLabels, labelStyle) {
        measureRowLabels(textMeasurer, labels.rowLabels, labelStyle)
    }
    val selectedRowLabelTexts = remember(textMeasurer, labels.rowLabels, selectedLabelStyle) {
        // A selected row without a label shows "–".
        measureRowLabels(textMeasurer, labels.rowLabels.map { it ?: "–" }, selectedLabelStyle)
    }
    val labelWidth = remember(rowLabelTexts, density) {
        rowLabelWidth(rowLabelTexts, minWidth = with(density) { 18.dp.toPx() }) + with(density) { 4.dp.toPx() }
    }
    val metrics = remember(density, labelWidth) { editorMetrics(density, labelWidth) }
    val geometry = remember(draft.layout, metrics, draft.screenWidth, draft.screenDistance, draft.screenOffset) {
        SeatMapGeometry(draft.layout, metrics, draft.screenWidth, draft.screenDistance, draft.screenOffset)
    }
    val maxZoomScale = with(density) { MAX_ZOOM_SEAT_SIZE.toPx() } / metrics.seatSize
    val numbersMinScale = seatNumbersMinScale(metrics.seatSize, density)
    val handleTouchRadius = with(density) { HANDLE_TOUCH_RADIUS.toPx() }
    val screenLabelHeight = with(density) { SCREEN_LABEL_HEIGHT.toPx() }
    val handleRoom = with(density) { HANDLE_ROOM.toPx() }

    // Measured numbers don't depend on the layout: kept across every edit.
    val numbers = remember(textMeasurer, numberStyle, metrics.seatSize, density) {
        SeatNumberTexts(textMeasurer, numberStyle, metrics.seatSize, density)
    }
    // Rebuilt when the layout or its labels change, not when the selection or the view does.
    val grid = remember(geometry, labels, colors, rowLabelTexts, selectedRowLabelTexts, numbers, density) {
        EditorGridDrawing(geometry, labels, colors, rowLabelTexts, selectedRowLabelTexts, numbers, density)
    }
    // In reference mode a tap aligns the screenshot instead of selecting the screen.
    val labelText = screenLabelText(screenSelected, tappable = !referenceMode, draft.screenDistance, draft.screenOffset)
    val screenLabel = remember(textMeasurer, labelText, screenSelected, screenLabelStyle, density) {
        ScreenLabel(textMeasurer.measure(labelText, screenLabelStyle), screenSelected, density)
    }

    val currentGeometry by rememberUpdatedState(geometry)
    val currentDraft by rememberUpdatedState(draft)
    val currentActions by rememberUpdatedState(actions)
    val currentMode by rememberUpdatedState(canvasMode(referenceMode, referenceImage != null))
    val currentAlignment by rememberUpdatedState(referenceAlignment)
    val currentScreenSelected by rememberUpdatedState(screenSelected)
    // Read only while drawing, so changing them (e.g. every frame while aligning the
    // screenshot) redraws without rebuilding the grid's prepared paths.
    val selectedRowState = rememberUpdatedState(selectedRow)
    val imageState = rememberUpdatedState(referenceImage)
    val opacityState = rememberUpdatedState(referenceOpacity)
    val alignmentState = rememberUpdatedState(referenceAlignment)
    val screenSelectedState = rememberUpdatedState(screenSelected)
    val screenLabelState = rememberUpdatedState(screenLabel)
    fun bounds(size: Size) = editorViewBounds(size, currentGeometry, maxZoomScale, screenLabelHeight, handleRoom)

    // As tall as the grid fitted to the width (plus the screen label's room above it),
    // within the limits. The hint line and the "Fit" control sit below the canvas
    // (CanvasFooter), so they never cover the drawing.
    Spacer(
        modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val fitted = editorViewBounds(Size(width.toFloat(), 0f), geometry, maxZoomScale, screenLabelHeight, handleRoom)
                val height = (screenLabelHeight + fitted.content.height * fitted.fitScale + BOTTOM_SPACE.toPx()).roundToInt()
                    .coerceIn(minHeight.roundToPx(), maxHeight.roundToPx())
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .clipToBounds()
            .semantics {
                contentDescription = "Layout canvas: ${draft.rowCount} rows, ${draft.columnCount} columns, " +
                    "${draft.layout.seatCount} seats. Tap or drag across cells to paint them."
            }
            .pointerInput(viewState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val viewport = size.toSize()
                    val g = currentGeometry
                    val transform = viewState.transformFor(bounds(viewport))
                    val zoomPan: (Offset, Offset, Float) -> Unit = { centroid, pan, zoom ->
                        val b = bounds(viewport)
                        viewState.userTransform = b.pinched(viewState.transformFor(b), centroid, pan, zoom)
                    }
                    val slop = viewConfiguration.touchSlop
                    // Outside the cells, one finger pans only when there is something to pan,
                    // so it can scroll the screen while the grid is fully visible.
                    val canPan = {
                        val b = bounds(viewport)
                        b.canPan(viewState.transformFor(b))
                    }
                    when (currentMode) {
                        CanvasMode.ALIGN_REFERENCE -> {
                            var alignment = currentAlignment ?: defaultReferenceAlignment(g)
                            trackZoomPan(slop, canPanWithOneFinger = { true }, onGesture = { centroid, pan, zoom ->
                                alignment = alignedBy(alignment, g, viewState.transformFor(bounds(viewport)), centroid, pan, zoom)
                                currentActions.onReferenceAlignment(alignment)
                            })
                            return@awaitEachGesture
                        }
                        CanvasMode.VIEW_ONLY -> {
                            trackZoomPan(slop, canPanWithOneFinger = canPan, onGesture = zoomPan)
                            return@awaitEachGesture
                        }
                        CanvasMode.EDIT -> Unit
                    }
                    val left = transform.toScreen(Offset(g.screenLeft, g.screenY))
                    val right = transform.toScreen(Offset(g.screenRight, g.screenY))
                    val label = screenLabelState.value.rect(left, right, viewport.width)
                    val hit = hitTest(down.position, g, transform, handleTouchRadius, label, currentScreenSelected)
                    when (hit) {
                        is CanvasHit.WidthHandle -> {
                            val start = currentDraft.screenWidth
                            val sign = if (hit.right) 1f else -1f
                            dragScreenControl(down, transform, viewState, currentActions) { total ->
                                currentActions.onScreenWidth(start + sign * 2f * total.x / (g.metrics.pitchX * transform.scale))
                            }
                        }
                        CanvasHit.ScreenBar -> {
                            // Touching the bar selects the screen; dragging it moves the screen,
                            // sideways or up / down, whichever way the drag starts.
                            currentActions.onSelectScreen()
                            val startDistance = currentDraft.screenDistance
                            val startOffset = currentDraft.screenOffset
                            var axis: DragAxis? = null
                            dragScreenControl(down, transform, viewState, currentActions) { total ->
                                val locked = axis ?: dragAxis(total, slop)?.also { axis = it }
                                when (locked) {
                                    DragAxis.HORIZONTAL ->
                                        currentActions.onScreenOffset(startOffset + total.x / (g.metrics.pitchX * transform.scale))
                                    DragAxis.VERTICAL ->
                                        currentActions.onScreenDistance(startDistance - total.y / (g.metrics.pitchY * transform.scale))
                                    null -> Unit
                                }
                            }
                        }
                        is CanvasHit.Cell -> paintOrZoom(
                            down = down,
                            cell = hit.position,
                            geometry = g,
                            transform = { viewState.transformFor(bounds(viewport)) },
                            slop = slop,
                            actions = currentActions,
                            zoomPan = zoomPan,
                        )
                        is CanvasHit.RowLabel -> {
                            if (awaitTapOrMove(down, slop)) currentActions.onSelectRow(hit.row) else {
                                trackZoomPan(slop, canPanWithOneFinger = canPan, onGesture = zoomPan)
                            }
                        }
                        CanvasHit.Empty -> {
                            if (awaitTapOrMove(down, slop)) currentActions.onTapOutside() else {
                                trackZoomPan(slop, canPanWithOneFinger = canPan, onGesture = zoomPan)
                            }
                        }
                    }
                }
            }
            .drawBehind {
                val transform = viewState.transformFor(editorViewBounds(size, geometry, maxZoomScale, screenLabelHeight, handleRoom))
                // The part of the drawing inside the canvas: seat numbers and symbols outside it are skipped.
                val visible = Rect(transform.toContent(Offset.Zero), transform.toContent(Offset(size.width, size.height)))
                val image = imageState.value
                withTransform({
                    translate(transform.offset.x, transform.offset.y)
                    scale(transform.scale, transform.scale, pivot = Offset.Zero)
                }) {
                    image?.let {
                        val alignment = alignmentState.value ?: defaultReferenceAlignment(geometry)
                        val rect = referenceRect(alignment, geometry, it)
                        drawImage(
                            it,
                            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                            alpha = opacityState.value,
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                    with(grid) {
                        draw(
                            selectedRow = selectedRowState.value,
                            showNumbers = transform.scale >= numbersMinScale,
                            overReference = image != null,
                            visible = visible,
                        )
                    }
                }
                drawScreenControls(geometry, transform, colors, screenLabelState.value, density, screenSelectedState.value)
            },
    )
}

/**
 * The text of the label above the screen bar: unselected, an invitation to tap
 * the screen ("SCREEN · tap to adjust"; just "SCREEN" where a tap can't select
 * it, i.e. not [tappable]); selected, its [distance] and [offset].
 */
internal fun screenLabelText(selected: Boolean, tappable: Boolean, distance: Float, offset: Float): String = when {
    selected -> "distance ${ScreenRules.distanceText(distance)} · ${ScreenRules.offsetText(offset).lowercase()}"
    tappable -> "SCREEN · tap to adjust"
    else -> "SCREEN"
}

/**
 * The label above the screen bar, measured once per text and drawn at a fixed
 * size on screen: the [text] alone, or for the [selected] screen a green "↕"
 * before it.
 */
internal class ScreenLabel(private val text: TextLayoutResult, private val selected: Boolean, density: Density) {
    private val dp = density.density
    private val arrowWidth = 10f * dp
    private val arrowHeight = 22f * dp
    private val arrowGap = 6f * dp

    /** The label's size without its backdrop. */
    private val labelSize: Size = if (selected) Size(arrowWidth + arrowGap + text.size.width, arrowHeight) else text.size.toSize()

    /** Between the label's bottom and the bar's line. */
    private val gap = (if (selected) 8f else 9f) * dp

    /** The "↕" in the label's coordinates. */
    private val arrow: Path? = if (!selected) {
        null
    } else {
        val x = arrowWidth / 2f
        Path().apply {
            moveTo(x, 1f * dp)
            lineTo(x, arrowHeight - 1f * dp)
            moveTo(x - 4f * dp, 5f * dp)
            lineTo(x, 1f * dp)
            lineTo(x + 4f * dp, 5f * dp)
            moveTo(x - 4f * dp, arrowHeight - 5f * dp)
            lineTo(x, arrowHeight - 1f * dp)
            lineTo(x + 4f * dp, arrowHeight - 5f * dp)
        }
    }
    private val arrowStroke = Stroke(width = 1.6f * dp, cap = StrokeCap.Round)

    /** Its backdrop's rect on screen for the bar from [left] to [right] (screen px), see [screenLabelRect]. */
    fun rect(left: Offset, right: Offset, canvasWidth: Float): Rect =
        screenLabelRect((left.x + right.x) / 2f, left.y, labelSize, gap, canvasWidth, dp)

    /** Draws it in [backdrop] (from [rect]); the backdrop keeps it readable over a reference screenshot. */
    fun DrawScope.draw(backdrop: Rect, colors: CinexColors) {
        drawRoundRect(colors.canvas.copy(alpha = 0.85f), backdrop.topLeft, backdrop.size, CornerRadius(6f * dp))
        val x = backdrop.left + LABEL_BACKDROP_MARGIN * dp
        val y = backdrop.top + LABEL_BACKDROP_MARGIN * dp
        if (arrow == null) {
            drawText(text, topLeft = Offset(x, y))
            return
        }
        translate(x, y) { drawPath(arrow, colors.editorSelection, style = arrowStroke) }
        drawText(text, topLeft = Offset(x + arrowWidth + arrowGap, y + (arrowHeight - text.size.height) / 2f))
    }
}

/**
 * A drag on the screen's controls from [down] until the finger lifts, as one undo
 * step; reports the total movement. The view is held still meanwhile (the
 * content's size changes with the screen), without counting as a zoom by the
 * user, so no "fit to view" button appears for it.
 */
private suspend fun AwaitPointerEventScope.dragScreenControl(
    down: PointerInputChange,
    transform: ViewTransform,
    viewState: SeatMapViewState,
    actions: EditorCanvasActions,
    onDrag: (total: Offset) -> Unit,
) {
    viewState.hold(transform)
    actions.onBeginGesture()
    try {
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                change.consume()
                break
            }
            onDrag(change.position - down.position)
            change.consume()
        }
    } finally {
        actions.onEndGesture()
        viewState.release()
    }
}

/** `true` for a tap (lifted before moving past [slop] and without a second finger), `false` otherwise. */
private suspend fun AwaitPointerEventScope.awaitTapOrMove(down: PointerInputChange, slop: Float): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) return false
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        if (!change.pressed) {
            change.consume()
            return true
        }
        if ((change.position - down.position).getDistance() > slop) return false
    }
}

/**
 * A touch on a cell: a tap paints it; moving past the touch slop starts a paint
 * stroke (one undo step) that follows the finger and fills every cell on the
 * way; a second finger (before or during the stroke) switches to zoom and pan.
 */
private suspend fun AwaitPointerEventScope.paintOrZoom(
    down: PointerInputChange,
    cell: CellPosition,
    geometry: SeatMapGeometry,
    /** The view as currently drawn (the fitted view may change while row labels change). */
    transform: () -> ViewTransform,
    slop: Float,
    actions: EditorCanvasActions,
    zoomPan: (Offset, Offset, Float) -> Unit,
) {
    var painting = false
    var last = down.position
    try {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                if (painting) {
                    painting = false
                    actions.onEndGesture()
                }
                trackZoomPan(slop, canPanWithOneFinger = { true }, onGesture = zoomPan, alreadyPastSlop = true)
                return
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: return
            if (!change.pressed) {
                change.consume()
                if (!painting) actions.onTapCell(cell.row, cell.column)
                return
            }
            if (!painting && (change.position - down.position).getDistance() > slop) {
                painting = true
                actions.onBeginGesture()
            }
            if (painting) {
                val view = transform()
                actions.onPaintCells(geometry.cellsAlong(view.toContent(last), view.toContent(change.position)))
                last = change.position
                change.consume()
            }
        }
    } finally {
        if (painting) actions.onEndGesture()
    }
}

/**
 * The grid in content coordinates, prepared once per layout (and label) change.
 * A seat's number and symbol are laid out the first time they are drawn.
 */
private class EditorGridDrawing(
    private val geometry: SeatMapGeometry,
    labels: LayoutLabels,
    private val colors: CinexColors,
    /** The row labels, measured ([measureRowLabels]). */
    private val rowLabels: List<TextLayoutResult?>,
    /** The row labels as shown on the selected row's chip ("–" for a row without one). */
    private val selectedRowLabels: List<TextLayoutResult?>,
    private val numbers: SeatNumberTexts,
    density: Density,
) {
    /** A seat with a kind symbol or a number. */
    private class SeatMarks(val rect: Rect, val kind: SeatKind, val number: String?) {
        /** The kind symbol (none on a standard seat), shown without a number. */
        val glyph: Path? = SeatGlyphs.of(kind)
        val symbolBox: Rect = if (glyph == null) Rect.Zero else rect.innerSquare(0.78f)
        private var prepared: NumberedSeat? = null

        /** Its number (and symbol), laid out on first use (only for a seat with a [number]). */
        fun numbered(numbers: SeatNumberTexts, number: String): NumberedSeat =
            prepared ?: NumberedSeat(rect, kind, SeatVisual.Neutral, numbers[number]).also { prepared = it }
    }

    private val dp = density.density
    private val seats = Path()
    private val gaps = Path()
    private val aisles = Path()
    private val hatch = Path()
    private val marks = ArrayList<SeatMarks>()
    private val hatchStroke = Stroke(width = 1.4f * dp)
    private val gapStroke = Stroke(width = 1f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f * dp, 2f * dp)))
    private val seatStroke = Stroke(width = 1.5f * dp)
    private val symbolStroke = glyphStroke()
    private val symbolHalo = glyphStroke(6f)

    /** How far a seat's number halo reaches beyond its cell. */
    private val decorationReach = geometry.metrics.seatSize * 0.25f

    init {
        val layout = geometry.layout
        val hatchSpacing = 4.2f * dp
        for (row in 0 until geometry.rowCount) {
            for (column in 0 until geometry.columnCount) {
                val rect = geometry.cellRect(row, column)
                when (val cell = layout.cell(row, column)) {
                    is LayoutCell.Seat -> {
                        seats.addRoundRect(seatRoundRect(rect.deflate(0.75f * dp)))
                        // Numbers (and fixed labels) come from the derived labels, so they follow
                        // every edit, also of the row's numbering direction.
                        marks += SeatMarks(rect, cell.kind, labels.labelOf(cell.id)?.seatNumberText)
                    }
                    LayoutCell.Gap -> gaps.addRoundRect(RoundRect(rect.deflate(0.5f * dp), CornerRadius(3f * dp)))
                    LayoutCell.Aisle -> {
                        aisles.addRect(rect)
                        addHatch(hatch, rect, hatchSpacing)
                    }
                }
            }
        }
    }

    /**
     * With [showNumbers], seats show their number or fixed label (wheelchair spaces,
     * companion and sofa places their symbol above it); otherwise only the kind
     * symbols are drawn. Over a
     * reference screenshot ([overReference]) numbers and symbols get a dark halo.
     * Numbers and symbols outside [visible] (the drawing inside the canvas) are skipped.
     */
    fun DrawScope.draw(selectedRow: Int?, showNumbers: Boolean, overReference: Boolean, visible: Rect) {
        if (selectedRow != null && selectedRow < geometry.rowCount) {
            val top = geometry.rowTop(selectedRow) - geometry.metrics.rowGap / 2f
            drawRect(
                colors.text.copy(alpha = 0.06f),
                topLeft = Offset(geometry.contentBounds.left, top),
                size = Size(geometry.contentBounds.width, geometry.metrics.pitchY),
            )
        }
        // Translucent, so a reference screenshot still shows through.
        drawPath(aisles, colors.surface.copy(alpha = 0.6f))
        clipPath(aisles, ClipOp.Intersect) {
            drawPath(hatch, colors.textMuted.copy(alpha = EDITOR_MARK_ALPHA), style = hatchStroke)
        }
        drawPath(gaps, colors.textMuted.copy(alpha = EDITOR_MARK_ALPHA), style = gapStroke)
        drawPath(seats, colors.text.copy(alpha = EDITOR_SEAT_FILL_ALPHA))
        drawPath(seats, colors.text.copy(alpha = 0.9f), style = seatStroke)
        val halo = if (overReference) colors.canvas else null
        val area = visible.inflate(decorationReach)
        for (seat in marks) {
            if (!seat.rect.overlaps(area)) continue
            val number = seat.number
            if (showNumbers && number != null) {
                with(seat.numbered(numbers, number)) { draw(colors, on = colors.text, halo = halo) }
            } else {
                val glyph = seat.glyph ?: continue
                if (halo != null) drawGlyph(glyph, seat.symbolBox, halo, symbolHalo)
                drawGlyph(glyph, seat.symbolBox, colors.text, symbolStroke)
            }
        }

        rowLabels.forEachIndexed { row, text ->
            val rect = geometry.leftLabelRect(row)
            if (row == selectedRow) {
                val chip = Rect(rect.left, rect.center.y - 9f * dp, rect.right, rect.center.y + 9f * dp)
                drawRoundRect(colors.text, topLeft = chip.topLeft, size = chip.size, cornerRadius = CornerRadius(4f * dp))
                selectedRowLabels[row]?.let { drawText(it, topLeft = centred(it, rect)) }
            } else if (text != null) {
                // A backdrop keeps labels readable over a reference screenshot.
                val backdrop = rect.inflate(1f * dp)
                drawRoundRect(colors.canvas.copy(alpha = 0.85f), backdrop.topLeft, backdrop.size, CornerRadius(4f * dp))
                drawText(text, topLeft = centred(text, rect))
            }
        }
    }

    private fun centred(text: TextLayoutResult, rect: Rect): Offset =
        Offset(rect.center.x - text.size.width / 2f, rect.center.y - text.size.height / 2f)
}

/** 45° hatch lines across [rect] (clipped to it when drawn). */
private fun addHatch(path: Path, rect: Rect, spacing: Float) {
    val step = spacing * sqrt(2f)
    var c = rect.left + rect.top - rect.height
    val end = rect.right + rect.bottom
    while (c <= end) {
        // Line x + y = c, drawn long enough to cross the whole cell.
        path.moveTo(c - rect.top + rect.height, rect.top - rect.height)
        path.lineTo(c - rect.bottom - rect.height, rect.bottom + rect.height)
        c += step
    }
}

/** "↔" in a 24 × 24 box: the symbol on the width handles. */
private val handleArrows: Path by lazy { PathParser().parsePathString("M5 12h14M8.5 8.5L5 12l3.5 3.5M15.5 8.5L19 12l-3.5 3.5").toPath() }

/**
 * The screen in screen coordinates (fixed size at every zoom): a straight bar
 * with its [label] above it, so nothing comes between the screen and row 0.
 * Unselected, the bar is light and the label invites a tap; [selected] (spec
 * §6.5), the bar turns green ([CinexColors.editorSelection]), round handles with
 * "↔" sit on its ends, and the label shows the distance and position.
 */
private fun DrawScope.drawScreenControls(
    geometry: SeatMapGeometry,
    transform: ViewTransform,
    colors: CinexColors,
    label: ScreenLabel,
    density: Density,
    selected: Boolean,
) {
    val dp = density.density
    val left = transform.toScreen(Offset(geometry.screenLeft, geometry.screenY))
    val right = transform.toScreen(Offset(geometry.screenRight, geometry.screenY))
    val barColor = if (selected) colors.editorSelection else colors.text
    val barWidth = (if (selected) 5f else 4f) * dp
    // A canvas-coloured outline keeps the bar's contrast over a reference screenshot.
    drawLine(colors.canvas, left, right, strokeWidth = barWidth + 2f * HANDLE_RING.value * dp, cap = StrokeCap.Round)
    drawLine(barColor, left, right, strokeWidth = barWidth, cap = StrokeCap.Round)
    if (selected) {
        val radius = HANDLE_RADIUS.value * dp
        fun handle(centre: Offset) {
            drawCircle(colors.canvas, radius = radius + HANDLE_RING.value * dp, center = centre)
            drawCircle(colors.editorSelection, radius = radius, center = centre)
            strokeGlyph(handleArrows, Rect(centre, radius * 0.8f), colors.onEditorSelection, strokeWidth = 2.6f)
        }
        handle(left)
        handle(right)
    }
    val canvasWidth = size.width
    with(label) { draw(rect(left, right, canvasWidth), colors) }
}
