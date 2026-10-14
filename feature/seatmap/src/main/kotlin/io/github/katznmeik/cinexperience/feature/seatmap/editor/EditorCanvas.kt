package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.ui.theme.CinexColors
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.seatmap.map.MAX_FIT_SCALE
import io.github.katznmeik.cinexperience.feature.seatmap.map.MAX_ZOOM_SEAT_SIZE
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatGlyphs
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatMapGeometry
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatMapMetrics
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatMapViewState
import io.github.katznmeik.cinexperience.feature.seatmap.map.ViewBounds
import io.github.katznmeik.cinexperience.feature.seatmap.map.ViewTransform
import io.github.katznmeik.cinexperience.feature.seatmap.map.innerSquare
import io.github.katznmeik.cinexperience.feature.seatmap.map.rowLabelWidth
import io.github.katznmeik.cinexperience.feature.seatmap.map.seatRoundRect
import io.github.katznmeik.cinexperience.feature.seatmap.map.strokeGlyph
import io.github.katznmeik.cinexperience.feature.seatmap.map.trackZoomPan
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
    fun onScreenWidth(width: Float)
    fun onScreenDistance(distance: Float)
    fun onReferenceAlignment(alignment: ReferenceAlignment)
}

/** Sizes of the editor drawing (bigger cells than the seat map, labels on the left only). */
internal fun editorMetrics(density: Density, labelWidth: Float): SeatMapMetrics = with(density) {
    SeatMapMetrics(
        seatSize = 15.dp.toPx(),
        seatGap = 3.dp.toPx(),
        rowGap = 3.dp.toPx(),
        labelWidth = labelWidth,
        labelGap = 5.dp.toPx(),
        screenTopSpace = HANDLE_RADIUS.toPx() + 4.dp.toPx(),
        minScreenGap = 0f,
        padding = 12.dp.toPx(),
        rightLabels = false,
    )
}

private val HANDLE_RADIUS = 7.dp
private val HANDLE_TOUCH_RADIUS = 24.dp

/** Room below the grid for the hint line. */
private val HINT_SPACE = 28.dp

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
    /** Paint cells, select rows, drag the screen handles; two fingers zoom. */
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
    data class WidthHandle(val right: Boolean) : CanvasHit
    data object DistanceHandle : CanvasHit
    data class Cell(val position: CellPosition) : CanvasHit
    data class RowLabel(val row: Int) : CanvasHit
    data object Empty : CanvasHit
}

/**
 * Hit-test of a screen point: above the grid, the screen's width handles first
 * (a finger-sized circle), then the screen bar and its distance label; then
 * cells and row labels. The screen controls never reach into row 0, so a wide
 * hall or a small screen distance can't turn a paint stroke into a screen drag.
 */
internal fun hitTest(
    point: Offset,
    geometry: SeatMapGeometry,
    transform: ViewTransform,
    handleTouchRadius: Float,
    distanceLabelHeight: Float,
): CanvasHit {
    // Top of row 0's band (cells own half of the row gap above them, see cellAt).
    val gridTop = transform.toScreen(Offset(0f, -geometry.metrics.rowGap / 2f)).y
    if (point.y < gridTop) {
        val left = transform.toScreen(Offset(geometry.screenLeft, geometry.screenY))
        val right = transform.toScreen(Offset(geometry.screenRight, geometry.screenY))
        val toRight = (point - right).getDistance()
        val toLeft = (point - left).getDistance()
        if (min(toLeft, toRight) <= handleTouchRadius) return CanvasHit.WidthHandle(right = toRight <= toLeft)
        val onBar = point.x in left.x..right.x && abs(point.y - left.y) <= handleTouchRadius * 0.6f
        val centre = (left.x + right.x) / 2f
        val onLabel = abs(point.x - centre) <= handleTouchRadius * 3f && point.y in left.y..left.y + distanceLabelHeight
        if (onBar || onLabel) return CanvasHit.DistanceHandle
    }
    val content = transform.toContent(point)
    geometry.cellAt(content)?.let { return CanvasHit.Cell(it) }
    geometry.rowLabelAt(content)?.let { return CanvasHit.RowLabel(it) }
    return CanvasHit.Empty
}

/**
 * The layout editor's canvas (spec §6.5): the grid with seats as outlined
 * squares, gaps dashed and aisles hatched, the screen bar with drag handles, and
 * the reference screenshot behind everything.
 *
 * Gestures: tap a cell to paint it, drag across cells to paint a stroke (one undo
 * step), tap a row label to select the row, drag the round handles to change the
 * screen width and the bar or its distance label to change the distance, and use
 * two fingers (or one finger outside the grid) to zoom and pan. In reference
 * mode, one or two fingers move and scale the screenshot instead.
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
    minHeight: Dp = 240.dp,
    maxHeight: Dp = 480.dp,
) {
    val density = LocalDensity.current
    val colors = CinexTheme.colors
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp, color = colors.textMuted)
    val selectedLabelStyle = labelStyle.copy(color = colors.onPrimary, fontWeight = FontWeight.SemiBold)
    val distanceStyle = MaterialTheme.typography.labelSmall.copy(color = colors.text.copy(alpha = 0.8f))

    val labelWidth = remember(labels.rowLabels, density) {
        rowLabelWidth(textMeasurer, labels.rowLabels, labelStyle, minWidth = with(density) { 18.dp.toPx() }) +
            with(density) { 4.dp.toPx() }
    }
    val metrics = remember(density, labelWidth) { editorMetrics(density, labelWidth) }
    val geometry = remember(draft.layout, metrics, draft.screenWidth, draft.screenDistance) {
        SeatMapGeometry(draft.layout, metrics, draft.screenWidth, draft.screenDistance)
    }
    val maxZoomScale = with(density) { MAX_ZOOM_SEAT_SIZE.toPx() } / metrics.seatSize
    val handleTouchRadius = with(density) { HANDLE_TOUCH_RADIUS.toPx() }
    val distanceLabelHeight = with(density) { 34.dp.toPx() }

    val currentGeometry by rememberUpdatedState(geometry)
    val currentDraft by rememberUpdatedState(draft)
    val currentActions by rememberUpdatedState(actions)
    val currentMode by rememberUpdatedState(canvasMode(referenceMode, referenceImage != null))
    val currentAlignment by rememberUpdatedState(referenceAlignment)
    // Read only while drawing, so changing them (e.g. every frame while aligning the
    // screenshot) redraws without rebuilding the grid's cached paths.
    val selectedRowState = rememberUpdatedState(selectedRow)
    val imageState = rememberUpdatedState(referenceImage)
    val opacityState = rememberUpdatedState(referenceOpacity)
    val alignmentState = rememberUpdatedState(referenceAlignment)
    fun bounds(size: Size) = ViewBounds.of(size, currentGeometry.contentBounds, MAX_FIT_SCALE, maxZoomScale)

    // As tall as the grid fitted to the width (plus room for the hint), within the limits.
    Box(
        modifier.layout { measurable, constraints ->
            val content = geometry.contentBounds
            val width = constraints.maxWidth
            val fit = min(width / max(content.width, 1f), MAX_FIT_SCALE)
            val height = (content.height * fit + HINT_SPACE.toPx()).roundToInt()
                .coerceIn(minHeight.roundToPx(), maxHeight.roundToPx())
                .coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
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
                        when (val hit = hitTest(down.position, g, transform, handleTouchRadius, distanceLabelHeight)) {
                            is CanvasHit.WidthHandle -> {
                                viewState.userTransform = transform
                                val start = currentDraft.screenWidth
                                val sign = if (hit.right) 1f else -1f
                                dragHandle(down, currentActions) { total ->
                                    currentActions.onScreenWidth(start + sign * 2f * total.x / (g.metrics.pitchX * transform.scale))
                                }
                            }
                            CanvasHit.DistanceHandle -> {
                                viewState.userTransform = transform
                                val start = currentDraft.screenDistance
                                dragHandle(down, currentActions) { total ->
                                    currentActions.onScreenDistance(start - total.y / (g.metrics.pitchY * transform.scale))
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
                            CanvasHit.Empty -> trackZoomPan(slop, canPanWithOneFinger = canPan, onGesture = zoomPan)
                        }
                    }
                }
                .drawWithCache {
                    val grid = EditorGridDrawing(geometry, labels, colors, textMeasurer, labelStyle, selectedLabelStyle, density)
                    onDrawBehind {
                        val transform = viewState.transformFor(ViewBounds.of(size, geometry.contentBounds, MAX_FIT_SCALE, maxZoomScale))
                        withTransform({
                            translate(transform.offset.x, transform.offset.y)
                            scale(transform.scale, transform.scale, pivot = Offset.Zero)
                        }) {
                            imageState.value?.let { image ->
                                val alignment = alignmentState.value ?: defaultReferenceAlignment(geometry)
                                val rect = referenceRect(alignment, geometry, image)
                                drawImage(
                                    image,
                                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                                    dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                                    alpha = opacityState.value,
                                    filterQuality = FilterQuality.Medium,
                                )
                            }
                            with(grid) { draw(selectedRowState.value) }
                        }
                        drawScreenControls(geometry, transform, colors, textMeasurer, distanceStyle, density, geometry.screenDistance)
                    }
                },
        )
        val hint = when {
            referenceImage != null -> "Reference screenshot · ${(referenceOpacity * 100).roundToInt()} % opacity" +
                if (referenceMode) " · drag to align" else ""
            referenceMode -> "Add a screenshot below to trace it"
            else -> "Tap or drag to paint · two fingers to zoom"
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = colors.text.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.canvas.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Follows one finger from [down] until it lifts; reports the total movement. One undo step. */
private suspend fun AwaitPointerEventScope.dragHandle(
    down: PointerInputChange,
    actions: EditorCanvasActions,
    onDrag: (total: Offset) -> Unit,
) {
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

/** The grid in content coordinates, prepared once per layout change. */
private class EditorGridDrawing(
    private val geometry: SeatMapGeometry,
    labels: LayoutLabels,
    private val colors: CinexColors,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    selectedLabelStyle: TextStyle,
    density: Density,
) {
    private val dp = density.density
    private val seats = Path()
    private val gaps = Path()
    private val aisles = Path()
    private val hatch = Path()
    private val glyphs = ArrayList<Pair<Rect, SeatKind>>()
    private val rowLabels = labels.rowLabels.map { it?.let { label -> measurer.measure(label, labelStyle) } }
    private val selectedRowLabels = labels.rowLabels.map { it?.let { label -> measurer.measure(label, selectedLabelStyle) } }
    private val dash = PathEffect.dashPathEffect(floatArrayOf(2.5f * dp, 2f * dp))

    init {
        val layout = geometry.layout
        val hatchSpacing = 4.2f * dp
        for (row in 0 until geometry.rowCount) {
            for (column in 0 until geometry.columnCount) {
                val rect = geometry.cellRect(row, column)
                when (val cell = layout.cell(row, column)) {
                    is LayoutCell.Seat -> {
                        seats.addRoundRect(seatRoundRect(rect.deflate(0.75f * dp)))
                        if (cell.kind != SeatKind.STANDARD) glyphs += rect to cell.kind
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

    fun DrawScope.draw(selectedRow: Int?) {
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
            drawPath(hatch, colors.textMuted.copy(alpha = 0.35f), style = Stroke(width = 1.4f * dp))
        }
        drawPath(gaps, colors.textMuted.copy(alpha = 0.45f), style = Stroke(width = 1f * dp, pathEffect = dash))
        drawPath(seats, colors.text.copy(alpha = 0.12f))
        drawPath(seats, colors.text.copy(alpha = 0.9f), style = Stroke(width = 1.5f * dp))
        glyphs.forEach { (rect, kind) -> SeatGlyphs.of(kind)?.let { strokeGlyph(it, rect.innerSquare(0.78f), colors.text) } }

        rowLabels.forEachIndexed { row, text ->
            val rect = geometry.leftLabelRect(row)
            if (row == selectedRow) {
                val chip = Rect(rect.left, rect.center.y - 9f * dp, rect.right, rect.center.y + 9f * dp)
                drawRoundRect(colors.text, topLeft = chip.topLeft, size = chip.size, cornerRadius = CornerRadius(4f * dp))
                (selectedRowLabels[row] ?: measuredDash)?.let { drawText(it, topLeft = centred(it, rect)) }
            } else if (text != null) {
                // A backdrop keeps labels readable over a reference screenshot.
                val backdrop = rect.inflate(1f * dp)
                drawRoundRect(colors.canvas.copy(alpha = 0.85f), backdrop.topLeft, backdrop.size, CornerRadius(4f * dp))
                drawText(text, topLeft = centred(text, rect))
            }
        }
    }

    private val measuredDash = measurer.measure("–", selectedLabelStyle)

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

/** Screen bar with its two width handles and the distance label, in screen coordinates (fixed size). */
private fun DrawScope.drawScreenControls(
    geometry: SeatMapGeometry,
    transform: ViewTransform,
    colors: CinexColors,
    measurer: TextMeasurer,
    style: TextStyle,
    density: Density,
    distance: Float,
) {
    val dp = density.density
    val left = transform.toScreen(Offset(geometry.screenLeft, geometry.screenY))
    val right = transform.toScreen(Offset(geometry.screenRight, geometry.screenY))
    drawLine(colors.text, left, right, strokeWidth = 4f * dp, cap = StrokeCap.Round)
    for (handle in listOf(left, right)) {
        drawCircle(colors.background, radius = (HANDLE_RADIUS.value + 2f) * dp, center = handle)
        drawCircle(colors.text, radius = HANDLE_RADIUS.value * dp, center = handle)
    }
    val text = measurer.measure("distance ${ScreenRules.distanceText(distance)}", style)
    val arrowWidth = 10f * dp
    val arrowHeight = 22f * dp
    val total = arrowWidth + 6f * dp + text.size.width
    val x0 = (left.x + right.x) / 2f - total / 2f
    val y0 = left.y + 6f * dp
    val arrowX = x0 + arrowWidth / 2f
    val arrow = Path().apply {
        moveTo(arrowX, y0 + 1f * dp)
        lineTo(arrowX, y0 + arrowHeight - 1f * dp)
        moveTo(arrowX - 4f * dp, y0 + 5f * dp)
        lineTo(arrowX, y0 + 1f * dp)
        lineTo(arrowX + 4f * dp, y0 + 5f * dp)
        moveTo(arrowX - 4f * dp, y0 + arrowHeight - 5f * dp)
        lineTo(arrowX, y0 + arrowHeight - 1f * dp)
        lineTo(arrowX + 4f * dp, y0 + arrowHeight - 5f * dp)
    }
    drawPath(arrow, style.color, style = Stroke(width = 1.4f * dp, cap = StrokeCap.Round))
    drawText(text, topLeft = Offset(x0 + arrowWidth + 6f * dp, y0 + (arrowHeight - text.size.height) / 2f))
}
