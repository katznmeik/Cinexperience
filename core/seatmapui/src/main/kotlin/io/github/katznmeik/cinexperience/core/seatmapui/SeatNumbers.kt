package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.ui.theme.CinexColors

/*
 * Seat numbers inside the seats (spec §6.3, §6.4, §6.5), shared by the seat map
 * and the layout editor. Numbers are sized relative to the seat, in content
 * units: they grow with zoom, not with the system font size. Below
 * [MIN_SEAT_NUMBER_SIZE] on screen they are left out and the seats show their
 * marks as plain symbols; zooming in brings the numbers back.
 */

/**
 * The number drawn inside a seat: standard, companion and sofa places show their
 * seat number; wheelchair spaces have none (they keep their symbol).
 */
val SeatLabel.seatNumberText: String?
    get() = if (kind.isNumbered) number.toString() else null

/** The number text of every numbered seat, from the derived labels ([io.github.katznmeik.cinexperience.core.model.labels]). */
fun LayoutLabels.seatNumbers(): Map<SeatId, String> =
    buildMap { seats.forEach { (id, info) -> info.label.seatNumberText?.let { put(id, it) } } }

/** Font size of a seat number of up to two digits, as a fraction of the seat's width. */
const val SEAT_NUMBER_SIZE: Float = 0.54f

/** Widest a seat number may be, as a fraction of the seat's width; longer numbers get a smaller font. */
const val SEAT_NUMBER_MAX_WIDTH: Float = 0.8f

/**
 * Smallest on-screen font size of a seat number. A map whose fitted seats are
 * smaller than that (very wide halls) shows its numbers once zoomed in far enough.
 */
val MIN_SEAT_NUMBER_SIZE: Dp = 7.dp

/**
 * The lowest view scale at which seats of [seatSize] (content units) show their
 * numbers, i.e. a two-digit number is at least [MIN_SEAT_NUMBER_SIZE] tall.
 */
fun seatNumbersMinScale(seatSize: Float, density: Density): Float =
    with(density) { MIN_SEAT_NUMBER_SIZE.toPx() } / (seatSize * SEAT_NUMBER_SIZE)

/** Height of the digits relative to the font size (Instrument Sans, measured), for optical centring. */
private const val DIGIT_HEIGHT = 0.72f

/** Vertical centre of the number in a seat without marks, and below the marks band, as fractions of the seat. */
private const val NUMBER_CENTRE = 0.5f
private const val NUMBER_CENTRE_BELOW_MARKS = 0.69f

/** The marks band at the top of a numbered seat: top edge, height and side margin, as fractions of the seat. */
private const val MARKS_TOP = 0.07f
private const val MARKS_HEIGHT = 0.38f
private const val MARKS_SIDE = 0.08f
private const val MARKS_SPACING = 0.04f

/**
 * Measured seat numbers for seats [seatSize] wide (content px), cached per text.
 * Build one per seat size and layout; it is cheap, since a hall has few distinct
 * numbers.
 */
class SeatNumberTexts(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val seatSize: Float,
    private val density: Density,
) {
    /** One measured number and its font size in px. */
    class Measured(val layout: TextLayoutResult, val fontSize: Float)

    private val cache = HashMap<String, Measured>()

    /** Font size (px) of numbers of up to two digits. */
    val standardFontSize: Float = seatSize * SEAT_NUMBER_SIZE

    operator fun get(text: String): Measured = cache.getOrPut(text) {
        val first = measure(text, standardFontSize)
        val maxWidth = seatSize * SEAT_NUMBER_MAX_WIDTH
        if (first.layout.size.width <= maxWidth) first else measure(text, standardFontSize * maxWidth / first.layout.size.width)
    }

    private fun measure(text: String, fontPx: Float): Measured {
        val size = with(density) { fontPx.toSp() }
        val layout = measurer.measure(
            text,
            style.copy(fontSize = size, lineHeight = TextUnit.Unspecified, letterSpacing = (-0.02).em, textAlign = TextAlign.Center),
            softWrap = false,
            density = density,
        )
        return Measured(layout, fontPx)
    }

    companion object {
        /** The seat number style: [base] (the theme's label style) in semibold. */
        fun style(base: TextStyle): TextStyle = base.copy(fontWeight = FontWeight.SemiBold)
    }
}


/** Width of a number's halo, as a fraction of its font size (see [drawSeatNumber]). */
const val SEAT_NUMBER_HALO: Float = 0.24f

/**
 * Draws [number] centred in the seat [rect], below the marks band when
 * [belowMarks]. With a [halo] colour, the digits get a rim of it first, so they
 * keep their contrast over an unknown background (the editor's reference screenshot).
 */
fun DrawScope.drawSeatNumber(
    number: SeatNumberTexts.Measured,
    rect: Rect,
    color: Color,
    belowMarks: Boolean,
    halo: Color? = null,
) {
    val layout = number.layout
    val centreY = rect.top + rect.height * (if (belowMarks) NUMBER_CENTRE_BELOW_MARKS else NUMBER_CENTRE)
    val baseline = centreY + number.fontSize * DIGIT_HEIGHT / 2f
    val topLeft = Offset(rect.center.x - layout.size.width / 2f, baseline - layout.firstBaseline)
    if (halo != null) {
        drawText(
            layout,
            color = halo,
            topLeft = topLeft,
            drawStyle = Stroke(width = number.fontSize * SEAT_NUMBER_HALO, join = StrokeJoin.Round),
        )
    }
    // Fill explicitly: without a draw style the text paint keeps the halo's stroke.
    drawText(layout, color = color, topLeft = topLeft, drawStyle = Fill)
}

/** A symbol in the marks band of a numbered seat. */
internal enum class SeatMark { KIND, AVOID, FAVOURITE }

/** The marks a numbered seat of [kind] in [visual] carries, left to right. */
internal fun seatMarks(kind: SeatKind, visual: SeatVisual): List<SeatMark> = buildList {
    if (kind != SeatKind.STANDARD) add(SeatMark.KIND)
    if (visual.tone == SeatTone.AVOID) add(SeatMark.AVOID)
    if (visual.isFavourite) add(SeatMark.FAVOURITE)
}

/**
 * The square boxes of [count] marks in the band at the top of [rect], side by
 * side and centred (smaller when three share the band).
 */
internal fun marksBand(rect: Rect, count: Int): List<Rect> {
    if (count <= 0) return emptyList()
    val s = rect.width
    val spacing = s * MARKS_SPACING
    val size = minOf(s * MARKS_HEIGHT, (s * (1f - 2f * MARKS_SIDE) - spacing * (count - 1)) / count)
    val total = size * count + spacing * (count - 1)
    val top = rect.top + s * MARKS_TOP + (s * MARKS_HEIGHT - size) / 2f
    return List(count) { index ->
        val left = rect.center.x - total / 2f + index * (size + spacing)
        Rect(left, top, left + size, top + size)
    }
}

/**
 * A numbered seat's number and marks (spec §5, §7): the seat kind symbol
 * (companion, sofa), the avoid × and the favourite ★ side by side in a band at
 * the top, the number below them (centred when there are no marks), all in the
 * seat colour's on-colour ([on]; the layout editor passes its own, and a [halo]
 * over its reference screenshot). The favourite also gets its light outline. The
 * fill (and the "no data" rim) is drawn by the caller.
 */
fun DrawScope.drawNumberedSeat(
    rect: Rect,
    kind: SeatKind,
    visual: SeatVisual,
    number: SeatNumberTexts.Measured,
    colors: CinexColors,
    on: Color = colors.seat.onColorOf(visual.tone),
    halo: Color? = null,
) {
    if (visual.isFavourite) drawFavouriteOutline(rect, colors)
    val marks = seatMarks(kind, visual)
    marksBand(rect, marks.size).forEachIndexed { index, box ->
        val (glyph, width) = when (marks[index]) {
            SeatMark.KIND -> (SeatGlyphs.of(kind) ?: return@forEachIndexed) to 2.4f
            SeatMark.AVOID -> SeatGlyphs.markCross to 3.6f
            SeatMark.FAVOURITE -> SeatGlyphs.star to 0f
        }
        if (halo != null) strokeGlyph(glyph, box, halo, strokeWidth = width + 4f)
        if (width == 0f) fillGlyph(glyph, box, on) else strokeGlyph(glyph, box, on, strokeWidth = width)
    }
    drawSeatNumber(number, rect, on, belowMarks = marks.isNotEmpty(), halo = halo)
}

/** The favourite's light outline just outside the seat. */
internal fun DrawScope.drawFavouriteOutline(rect: Rect, colors: CinexColors) {
    val outline = rect.inflate(rect.width * 0.09f)
    drawPath(Path().apply { addRoundRect(seatRoundRect(outline)) }, colors.text, style = Stroke(width = rect.width * 0.08f))
}
