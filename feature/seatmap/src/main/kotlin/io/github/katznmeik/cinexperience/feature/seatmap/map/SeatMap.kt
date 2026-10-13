package io.github.katznmeik.cinexperience.feature.seatmap.map

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.ui.theme.CinexColors
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom and pan of a [SeatMap] (or the layout editor canvas). Until the user zooms
 * or pans, the map follows its fitted view, also when the content changes.
 */
@Stable
class SeatMapViewState {
    /** The user's zoom and pan; `null` = fitted view. */
    var userTransform: ViewTransform? by mutableStateOf(null)

    val isAdjusted: Boolean get() = userTransform != null

    /** Back to the fitted view. */
    fun reset() {
        userTransform = null
    }

    fun transformFor(bounds: ViewBounds): ViewTransform = userTransform?.let(bounds::clamp) ?: bounds.fitted()
}

@Composable
fun rememberSeatMapViewState(): SeatMapViewState = remember { SeatMapViewState() }

/** The fitted view never magnifies small halls beyond this. */
internal const val MAX_FIT_SCALE: Float = 2.5f

/** Zooming in goes at least this far: a seat about this wide on screen. */
internal val MAX_ZOOM_SEAT_SIZE: Dp = 44.dp

/**
 * The seat map of one hall (spec §6.3, §4.3): screen arc at the top, the seat
 * grid with row labels on both sides, staggered rows shifted by half a seat,
 * aisles and gaps as empty space.
 *
 * Drawn on one canvas (no composable per seat) and hit-tested with
 * [SeatMapGeometry.seatAt], so it stays smooth for big halls. It starts fitted
 * to its width; pinch to zoom, double-tap to zoom in or back, and drag to pan
 * once zoomed in (at the fitted size one-finger drags scroll the screen around
 * it). Its height follows the fitted content, up to [maxHeight].
 *
 * @param contentDescription what the map shows, e.g. from [seatMapDescription].
 * @param seatVisuals colour, favourite and avoid states (M4); missing seats are neutral.
 * @param onSeatClick called with the tapped seat; `null` makes the map display-only.
 * @param onChooseSeatByLabel accessibility alternative to tapping a seat: shown to
 *   screen readers as a custom action (e.g. opening a [SeatPicker]).
 */
@Composable
fun SeatMap(
    layout: SeatLayout,
    contentDescription: String,
    modifier: Modifier = Modifier,
    labels: LayoutLabels = remember(layout) { layout.labels() },
    screenWidth: Float? = null,
    screenDistance: Float? = null,
    seatVisuals: Map<SeatId, SeatVisual> = emptyMap(),
    selectedSeatId: SeatId? = null,
    onSeatClick: ((SeatId) -> Unit)? = null,
    onChooseSeatByLabel: (() -> Unit)? = null,
    maxHeight: Dp = 520.dp,
    viewState: SeatMapViewState = rememberSeatMapViewState(),
) {
    val density = LocalDensity.current
    val colors = CinexTheme.colors
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, color = colors.textMuted)
    val captionStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 2.sp,
        color = colors.textMuted,
    )

    val labelWidth = remember(labels.rowLabels, labelStyle, density) {
        rowLabelWidth(textMeasurer, labels.rowLabels, labelStyle, minWidth = with(density) { 14.dp.toPx() })
    }
    val metrics = remember(density, labelWidth) { seatMapMetrics(density, labelWidth) }
    val geometry = remember(layout, metrics, screenWidth, screenDistance) {
        SeatMapGeometry(layout, metrics, screenWidth, screenDistance)
    }
    val maxZoomScale = with(density) { MAX_ZOOM_SEAT_SIZE.toPx() } / metrics.seatSize
    val tapTolerance = with(density) { 10.dp.toPx() }

    val currentGeometry by rememberUpdatedState(geometry)
    val currentOnSeatClick by rememberUpdatedState(onSeatClick)
    fun bounds(size: Size) = ViewBounds.of(size, currentGeometry.contentBounds, MAX_FIT_SCALE, maxZoomScale)

    val selectedLabel = selectedSeatId?.let { labels.labelOf(it) }
    Spacer(
        modifier
            .layout { measurable, constraints ->
                val content = geometry.contentBounds
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else ceil(content.width).toInt()
                val fit = min(width / max(content.width, 1f), MAX_FIT_SCALE)
                val height = ceil(content.height * fit).toInt()
                    .coerceAtMost(maxHeight.roundToPx())
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .clipToBounds()
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
                if (selectedLabel != null) stateDescription = "Selected: ${selectedLabel.description}"
                if (onChooseSeatByLabel != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(CHOOSE_BY_LABEL_ACTION) {
                            onChooseSeatByLabel()
                            true
                        },
                    )
                }
            }
            .pointerInput(viewState) {
                detectZoomPan(
                    canPanWithOneFinger = {
                        val b = bounds(size.toSize())
                        b.canPan(viewState.transformFor(b))
                    },
                    onGesture = { centroid, pan, zoom ->
                        val b = bounds(size.toSize())
                        viewState.userTransform = b.pinched(viewState.transformFor(b), centroid, pan, zoom)
                    },
                )
            }
            .pointerInput(viewState) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        val b = bounds(size.toSize())
                        viewState.userTransform = b.toggleZoom(viewState.transformFor(b), position)
                    },
                    onTap = { position ->
                        val onClick = currentOnSeatClick ?: return@detectTapGestures
                        val transform = viewState.transformFor(bounds(size.toSize()))
                        currentGeometry.seatAt(transform.toContent(position), tapTolerance / transform.scale)?.let(onClick)
                    },
                )
            }
            .drawWithCache {
                val content = SeatMapDrawing(geometry, labels, seatVisuals, colors, textMeasurer, labelStyle, captionStyle, density)
                onDrawBehind {
                    val transform = viewState.transformFor(ViewBounds.of(size, geometry.contentBounds, MAX_FIT_SCALE, maxZoomScale))
                    withTransform({
                        translate(transform.offset.x, transform.offset.y)
                        scale(transform.scale, transform.scale, pivot = Offset.Zero)
                    }) {
                        with(content) { draw(selectedSeatId) }
                    }
                }
            },
    )
}

/** Label of the screen-reader action that offers choosing a seat by row and number. */
const val CHOOSE_BY_LABEL_ACTION: String = "Choose a seat by row and number"

private fun seatMapMetrics(density: Density, labelWidth: Float): SeatMapMetrics = with(density) {
    SeatMapMetrics(
        seatSize = 14.dp.toPx(),
        seatGap = 2.dp.toPx(),
        rowGap = 3.dp.toPx(),
        labelWidth = labelWidth,
        labelGap = 2.dp.toPx(),
        screenTopSpace = 12.dp.toPx(),
        minScreenGap = 34.dp.toPx(),
        padding = 2.dp.toPx(),
    )
}

/** Width of the row label column: the widest label, at least [minWidth]. */
internal fun rowLabelWidth(measurer: TextMeasurer, rowLabels: List<String?>, style: TextStyle, minWidth: Float): Float =
    max(minWidth, rowLabels.filterNotNull().maxOfOrNull { measurer.measure(it, style).size.width.toFloat() } ?: 0f)

/** The map's drawing in content coordinates, prepared once per layout and visual change. */
private class SeatMapDrawing(
    private val geometry: SeatMapGeometry,
    labels: LayoutLabels,
    private val visuals: Map<SeatId, SeatVisual>,
    private val colors: CinexColors,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    captionStyle: TextStyle,
    density: Density,
) {
    private val dp = density.density
    private val seatPaths: Map<SeatTone, Path>
    private val decorated = ArrayList<Triple<Rect, SeatKind, SeatVisual>>()
    private val rowLabels: List<TextLayoutResult?> = labels.rowLabels.map { it?.let { label -> measurer.measure(label, labelStyle) } }
    private val caption = measurer.measure("SCREEN", captionStyle)
    private val screenArc = Path()

    init {
        val paths = HashMap<SeatTone, Path>()
        val layout = geometry.layout
        for (row in 0 until geometry.rowCount) {
            for (column in 0 until geometry.columnCount) {
                val cell = layout.cell(row, column) as? LayoutCell.Seat ?: continue
                val visual = visuals[cell.id] ?: SeatVisual.Neutral
                val rect = geometry.cellRect(row, column)
                paths.getOrPut(visual.tone) { Path() }.addRoundRect(seatRoundRect(rect))
                if (cell.kind != SeatKind.STANDARD || visual.isFavourite || visual.tone == SeatTone.AVOID) {
                    decorated += Triple(rect, cell.kind, visual)
                }
            }
        }
        seatPaths = paths
        val bulge = min((geometry.screenRight - geometry.screenLeft) * 0.045f, 10f * dp)
        screenArc.moveTo(geometry.screenLeft, geometry.screenY)
        screenArc.quadraticTo(geometry.screenCenterX, geometry.screenY - 2f * bulge, geometry.screenRight, geometry.screenY)
    }

    fun DrawScope.draw(selected: SeatId?) {
        drawPath(screenArc, colors.text, style = Stroke(width = 3f * dp, cap = StrokeCap.Round))
        drawText(caption, topLeft = Offset(geometry.screenCenterX - caption.size.width / 2f, geometry.screenY + 5f * dp))

        rowLabels.forEachIndexed { row, text ->
            if (text == null) return@forEachIndexed
            drawText(text, topLeft = centred(text, geometry.leftLabelRect(row)))
            geometry.rightLabelRect(row)?.let { drawText(text, topLeft = centred(text, it)) }
        }

        seatPaths.forEach { (tone, path) -> drawPath(path, colors.seat.colorOf(tone)) }
        decorated.forEach { (rect, kind, visual) -> drawSeatMarks(rect, kind, visual, colors) }

        selected?.let { id ->
            geometry.layout.positionOf(id)?.let { position ->
                val ring = geometry.cellRect(position.row, position.column).inflate(2.5f * dp)
                drawPath(Path().apply { addRoundRect(seatRoundRect(ring)) }, colors.text, style = Stroke(width = 2f * dp))
            }
        }
    }

    private fun centred(text: TextLayoutResult, rect: Rect): Offset =
        Offset(rect.center.x - text.size.width / 2f, rect.center.y - text.size.height / 2f)
}

/**
 * The kind symbol, favourite star and avoid × of one seat. On a standard seat the
 * mark sits in the middle; on a wheelchair, companion or sofa seat the symbol
 * stays and the marks become small badges in the corners.
 */
internal fun DrawScope.drawSeatMarks(rect: Rect, kind: SeatKind, visual: SeatVisual, colors: CinexColors) {
    val onSeat = if (visual.tone == SeatTone.NO_DATA) colors.text else colors.onPrimary
    if (visual.isFavourite) {
        val outline = rect.inflate(rect.width * 0.1f)
        drawPath(Path().apply { addRoundRect(seatRoundRect(outline)) }, colors.text, style = Stroke(width = rect.width * 0.1f))
    }
    val glyph = SeatGlyphs.of(kind)
    if (glyph == null) {
        when {
            visual.isFavourite -> fillGlyph(SeatGlyphs.star, rect.innerSquare(0.72f), onSeat)
            visual.tone == SeatTone.AVOID -> strokeGlyph(SeatGlyphs.cross, rect.innerSquare(0.62f), colors.surface, strokeWidth = 3.2f)
        }
        return
    }
    strokeGlyph(glyph, rect.innerSquare(0.8f), onSeat)
    // Badges sit in the seat's top corners, barely past its edge (less than the seat gap).
    val badge = rect.width * 0.24f
    val inset = rect.width * 0.2f
    if (visual.isFavourite) {
        val centre = Offset(rect.right - inset, rect.top + inset)
        drawCircle(colors.text, radius = badge, center = centre)
        fillGlyph(SeatGlyphs.star, Rect(centre, badge * 0.8f), colors.onPrimary)
    }
    if (visual.tone == SeatTone.AVOID) {
        val centre = Offset(rect.left + inset, rect.top + inset)
        drawCircle(colors.surface, radius = badge, center = centre)
        strokeGlyph(SeatGlyphs.cross, Rect(centre, badge * 0.7f), colors.text, strokeWidth = 3.2f)
    }
}
