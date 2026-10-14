package io.github.katznmeik.cinexperience.core.seatmapui

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

    /**
     * The view held still during a drag that changes the content's size (e.g. the
     * layout editor's screen handles), so the dragged thing stays under the finger.
     * It is not a zoom by the user: [isAdjusted] stays false, and after [release]
     * the map follows its fitted view again.
     */
    private var heldTransform: ViewTransform? by mutableStateOf(null)

    val isAdjusted: Boolean get() = userTransform != null

    /** Back to the fitted view. */
    fun reset() {
        userTransform = null
    }

    /** Holds [transform] (the view as drawn) until [release], unless the user has zoomed anyway. */
    fun hold(transform: ViewTransform) {
        if (userTransform == null) heldTransform = transform
    }

    fun release() {
        heldTransform = null
    }

    fun transformFor(bounds: ViewBounds): ViewTransform = (userTransform ?: heldTransform)?.let(bounds::clamp) ?: bounds.fitted()
}

@Composable
fun rememberSeatMapViewState(): SeatMapViewState = remember { SeatMapViewState() }

/** The fitted view never magnifies small halls beyond this. */
const val MAX_FIT_SCALE: Float = 2.5f

/** Zooming in goes at least this far: a seat about this wide on screen. */
val MAX_ZOOM_SEAT_SIZE: Dp = 44.dp

/**
 * The seat map of one hall (spec §6.3, §4.3): the screen as a straight bar at the
 * top, the seat grid with row labels on both sides, staggered rows shifted by
 * half a seat, aisles and gaps as empty space. Every numbered seat shows its seat
 * number (from [labels]) once the seats are big enough on screen for it (see
 * [MIN_SEAT_NUMBER_SIZE]); wheelchair spaces show their symbol.
 *
 * Drawn on one canvas (no composable per seat) and hit-tested with
 * [SeatMapGeometry.seatAt], so it stays smooth for big halls. It starts fitted
 * to its width; pinch to zoom, double-tap to zoom in or back, and drag to pan
 * once zoomed in (at the fitted size one-finger drags scroll the screen around
 * it). Its height follows the fitted content, up to [maxHeight].
 *
 * @param contentDescription what the map shows, e.g. from [seatMapDescription].
 * @param screenWidth, screenDistance, screenOffset the hall's screen geometry (spec §4.2);
 *   the bar is drawn shifted by [screenOffset] seat widths (negative = left).
 * @param seatVisuals colour, favourite and avoid states (spec §5); missing seats are neutral.
 *   When it is not empty, the selected seat's state is part of its screen-reader
 *   description ("Selected: Row 7, Seat 10, great, favourite"). Pair the map with a
 *   [SeatLegend].
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
    screenOffset: Float = 0f,
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
    // Text drawn in the map is sized in dp, not sp: it grows with zoom instead of with
    // the system font size, and it has to fit the rows.
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = with(density) { 10.dp.toSp() },
        lineHeight = with(density) { 12.dp.toSp() },
        color = colors.textMuted,
    )
    val captionStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = with(density) { 10.dp.toSp() },
        lineHeight = with(density) { 13.dp.toSp() },
        letterSpacing = with(density) { 2.dp.toSp() },
        color = colors.textMuted,
    )
    val numberStyle = SeatNumberTexts.style(MaterialTheme.typography.labelSmall)

    val labelWidth = remember(labels.rowLabels, labelStyle, density) {
        rowLabelWidth(textMeasurer, labels.rowLabels, labelStyle, minWidth = with(density) { 12.dp.toPx() })
    }
    val metrics = remember(density, labelWidth) { seatMapMetrics(density, labelWidth) }
    val numbersMinScale = seatNumbersMinScale(metrics.seatSize, density)
    val geometry = remember(layout, metrics, screenWidth, screenDistance, screenOffset) {
        SeatMapGeometry(layout, metrics, screenWidth, screenDistance, screenOffset)
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
                if (selectedLabel != null) {
                    // With colours shown, the selected seat's state is announced as well.
                    val description = if (seatVisuals.isEmpty()) {
                        selectedLabel.description
                    } else {
                        seatDescription(selectedLabel, selectedSeatId?.let { seatVisuals[it] } ?: SeatVisual.Neutral)
                    }
                    stateDescription = "Selected: $description"
                }
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
                val numbers = SeatNumberTexts(textMeasurer, numberStyle, metrics.seatSize, density)
                val content = SeatMapDrawing(geometry, labels, seatVisuals, colors, textMeasurer, labelStyle, captionStyle, numbers, density)
                onDrawBehind {
                    val transform = viewState.transformFor(ViewBounds.of(size, geometry.contentBounds, MAX_FIT_SCALE, maxZoomScale))
                    withTransform({
                        translate(transform.offset.x, transform.offset.y)
                        scale(transform.scale, transform.scale, pivot = Offset.Zero)
                    }) {
                        with(content) { draw(selectedSeatId, showNumbers = transform.scale >= numbersMinScale) }
                    }
                }
            },
    )
}

/** Label of the screen-reader action that offers choosing a seat by row and number. */
const val CHOOSE_BY_LABEL_ACTION: String = "Choose a seat by row and number"

/**
 * Sizes of the map at zoom 1. The map is fitted to its width, so what counts are
 * the proportions: big seats with narrow gaps and slim row labels leave the most
 * room for the seat numbers (a 22-column hall gets about 14 dp seats on a
 * 412 dp phone).
 */
internal fun seatMapMetrics(density: Density, labelWidth: Float): SeatMapMetrics = with(density) {
    SeatMapMetrics(
        seatSize = 16.dp.toPx(),
        seatGap = 1.5.dp.toPx(),
        rowGap = 3.dp.toPx(),
        labelWidth = labelWidth,
        labelGap = 3.dp.toPx(),
        screenTopSpace = 3.dp.toPx(),
        minScreenGap = 30.dp.toPx(),
        // Room for the selection ring and the favourite outline around the outer seats.
        padding = 4.dp.toPx(),
    )
}

/**
 * Whether a [SeatMap] [width] px wide shows [layout]'s seat numbers at its fitted
 * zoom, i.e. without zooming in (spec §6.3). Row labels are taken as [labelWidth]
 * wide (the map's minimum; enough for labels up to "99").
 */
fun seatNumbersShownWhenFitted(
    layout: SeatLayout,
    width: Float,
    density: Density,
    screenWidth: Float? = null,
    screenOffset: Float = 0f,
    labelWidth: Float = with(density) { 12.dp.toPx() },
): Boolean {
    val metrics = seatMapMetrics(density, labelWidth)
    // A screen wider than the grid, or moved past its side, widens the content.
    val geometry = SeatMapGeometry(layout, metrics, screenWidth, null, screenOffset)
    val fit = ViewBounds.of(Size(width, 1f), geometry.contentBounds, MAX_FIT_SCALE, 1f).fitScale
    return fit >= seatNumbersMinScale(metrics.seatSize, density)
}

/** Width of the row label column: the widest label, at least [minWidth]. */
fun rowLabelWidth(measurer: TextMeasurer, rowLabels: List<String?>, style: TextStyle, minWidth: Float): Float =
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
    private val numbers: SeatNumberTexts,
    density: Density,
) {
    /** One seat with what is drawn on top of its fill. */
    private class SeatDecoration(val rect: Rect, val kind: SeatKind, val visual: SeatVisual, val number: String?)

    private val dp = density.density
    private val seatPaths: Map<SeatTone, Path>
    private val noDataRims = Path()
    private val seats = ArrayList<SeatDecoration>()
    private val rowLabels: List<TextLayoutResult?> = labels.rowLabels.map { it?.let { label -> measurer.measure(label, labelStyle) } }
    private val caption = measurer.measure("SCREEN", captionStyle)

    init {
        val paths = HashMap<SeatTone, Path>()
        val layout = geometry.layout
        for (row in 0 until geometry.rowCount) {
            for (column in 0 until geometry.columnCount) {
                val cell = layout.cell(row, column) as? LayoutCell.Seat ?: continue
                val visual = visuals[cell.id] ?: SeatVisual.Neutral
                val rect = geometry.cellRect(row, column)
                paths.getOrPut(visual.tone) { Path() }.addRoundRect(seatRoundRect(rect))
                if (visual.tone == SeatTone.NO_DATA) noDataRims.addRoundRect(seatRoundRect(rect.noDataRim(dp)))
                seats += SeatDecoration(rect, cell.kind, visual, labels.labelOf(cell.id)?.seatNumberText)
            }
        }
        seatPaths = paths
    }

    /**
     * With [showNumbers], numbered seats show their number with their marks in a band
     * above it ([drawNumberedSeat]); otherwise (and on wheelchair spaces) the marks
     * are drawn as symbols ([drawSeatMarks]).
     */
    fun DrawScope.draw(selected: SeatId?, showNumbers: Boolean) {
        // The screen: a straight bar (spec §7), centred over its position.
        drawLine(
            colors.text,
            Offset(geometry.screenLeft, geometry.screenY),
            Offset(geometry.screenRight, geometry.screenY),
            strokeWidth = 3f * dp,
            cap = StrokeCap.Round,
        )
        drawText(caption, topLeft = Offset(geometry.screenCenterX - caption.size.width / 2f, geometry.screenY + 5f * dp))

        rowLabels.forEachIndexed { row, text ->
            if (text == null) return@forEachIndexed
            drawText(text, topLeft = centred(text, geometry.leftLabelRect(row)))
            geometry.rightLabelRect(row)?.let { drawText(text, topLeft = centred(text, it)) }
        }

        seatPaths.forEach { (tone, path) -> drawPath(path, colors.seat.colorOf(tone)) }
        drawPath(noDataRims, colors.seat.noDataOutline, style = Stroke(width = NO_DATA_RIM_WIDTH * dp))
        for (seat in seats) {
            val number = seat.number
            when {
                showNumbers && number != null -> drawNumberedSeat(seat.rect, seat.kind, seat.visual, numbers[number], colors)
                seat.kind != SeatKind.STANDARD || seat.visual.isFavourite || seat.visual.tone == SeatTone.AVOID ->
                    drawSeatMarks(seat.rect, seat.kind, seat.visual, colors)
            }
        }

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
 * The kind symbol, favourite star and avoid × of a seat shown without a number
 * (wheelchair spaces, the legend, and every seat while the map is zoomed out too
 * far for numbers). On a standard seat the mark sits in the middle; on a
 * wheelchair, companion or sofa seat the symbol stays and the marks become small
 * badges in the corners. Numbered seats with their number use [drawNumberedSeat].
 */
internal fun DrawScope.drawSeatMarks(rect: Rect, kind: SeatKind, visual: SeatVisual, colors: CinexColors) {
    val onSeat = colors.seat.onColorOf(visual.tone)
    if (visual.isFavourite) drawFavouriteOutline(rect, colors)
    // Badges sit in the seat's top corners, barely past its edge (less than the seat gap).
    val badge = rect.width * 0.24f
    val inset = rect.width * 0.2f
    fun avoidBadge() {
        val centre = Offset(rect.left + inset, rect.top + inset)
        drawCircle(colors.surface, radius = badge, center = centre)
        strokeGlyph(SeatGlyphs.cross, Rect(centre, badge * 0.7f), colors.text, strokeWidth = 3.2f)
    }
    val glyph = SeatGlyphs.of(kind)
    if (glyph == null) {
        when {
            visual.isFavourite -> {
                fillGlyph(SeatGlyphs.star, rect.innerSquare(0.72f), onSeat)
                // A favourite marked "avoid" keeps its × as a badge (colour-blind cue).
                if (visual.tone == SeatTone.AVOID) avoidBadge()
            }
            visual.tone == SeatTone.AVOID -> strokeGlyph(SeatGlyphs.cross, rect.innerSquare(0.62f), onSeat, strokeWidth = 3.2f)
        }
        return
    }
    strokeGlyph(glyph, rect.innerSquare(0.8f), onSeat)
    if (visual.isFavourite) {
        val centre = Offset(rect.right - inset, rect.top + inset)
        drawCircle(colors.text, radius = badge, center = centre)
        fillGlyph(SeatGlyphs.star, Rect(centre, badge * 0.8f), colors.onPrimary)
    }
    if (visual.tone == SeatTone.AVOID) avoidBadge()
}
