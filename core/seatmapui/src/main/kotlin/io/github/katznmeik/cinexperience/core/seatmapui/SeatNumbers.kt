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
 * It doesn't depend on the layout: remember one per measurer, style, seat size
 * and density, so edits and selections reuse the measured numbers.
 *
 * Numbers too wide for [SEAT_NUMBER_MAX_WIDTH] shrink by their number of digits,
 * not each on its own: every three-digit number gets the font size at which the
 * widest three digits fit, so "100" and "101" look alike.
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
    private val fontSizes = HashMap<Int, Float>()
    private val maxWidth = seatSize * SEAT_NUMBER_MAX_WIDTH

    /** Font size (px) of numbers of up to two digits. */
    val standardFontSize: Float = seatSize * SEAT_NUMBER_SIZE

    /** The digit that is widest in the number font, measured once. */
    private val widestDigit: Char by lazy {
        ('0'..'9').maxBy { measure(it.toString(), standardFontSize).layout.size.width }
    }

    /**
     * Font size (px) of every number with [length] digits: [standardFontSize], or
     * smaller so that [length] of the widest digit fit [SEAT_NUMBER_MAX_WIDTH].
     */
    fun fontSizeFor(length: Int): Float = fontSizes.getOrPut(length) {
        val widest = measure(widestDigit.toString().repeat(length.coerceAtLeast(1)), standardFontSize).layout.size.width
        if (widest <= maxWidth) standardFontSize else standardFontSize * maxWidth / widest
    }

    operator fun get(text: String): Measured = cache.getOrPut(text) {
        val measured = measure(text, fontSizeFor(text.length))
        val width = measured.layout.size.width
        // Digits are never wider than the widest digit, so all numbers of one length keep
        // one size; anything else (not a seat number) is shrunk on its own if it must.
        if (width <= maxWidth || text.all { it in '0'..'9' }) measured else measure(text, measured.fontSize * maxWidth / width)
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


/** Width of a number's halo, as a fraction of its font size (see [NumberedSeat]). */
const val SEAT_NUMBER_HALO: Float = 0.24f

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
 * A numbered seat's number and marks (spec §5, §7), laid out once per layout and
 * seat states, so drawing it every frame allocates nothing: the seat kind symbol
 * (companion, sofa), the avoid × and the favourite ★ side by side in a band at
 * the top of [rect], the [number] below them (centred when there are no marks).
 * The favourite also gets its light outline. The fill (and the "no data" rim) is
 * drawn by the caller.
 */
class NumberedSeat(
    val rect: Rect,
    kind: SeatKind,
    visual: SeatVisual,
    private val number: SeatNumberTexts.Measured,
) {
    /** A symbol in the marks band: stroked with [stroke], or filled without one; [halo] is its rim. */
    private class Mark(val glyph: Path, val box: Rect, val stroke: Stroke?, val halo: Stroke)

    private val favourite: FavouriteOutline? = if (visual.isFavourite) FavouriteOutline(rect) else null
    private val marks: Array<Mark>
    private val numberTopLeft: Offset
    private val numberHalo = Stroke(width = number.fontSize * SEAT_NUMBER_HALO, join = StrokeJoin.Round)

    init {
        val kinds = seatMarks(kind, visual)
        val boxes = marksBand(rect, kinds.size)
        marks = kinds.mapIndexedNotNull { index, mark ->
            val (glyph, width) = when (mark) {
                SeatMark.KIND -> (SeatGlyphs.of(kind) ?: return@mapIndexedNotNull null) to 2.4f
                SeatMark.AVOID -> SeatGlyphs.markCross to 3.6f
                SeatMark.FAVOURITE -> SeatGlyphs.star to 0f
            }
            Mark(glyph, boxes[index], stroke = if (width == 0f) null else glyphStroke(width), halo = glyphStroke(width + 4f))
        }.toTypedArray()
        val centreY = rect.top + rect.height * (if (kinds.isNotEmpty()) NUMBER_CENTRE_BELOW_MARKS else NUMBER_CENTRE)
        val baseline = centreY + number.fontSize * DIGIT_HEIGHT / 2f
        numberTopLeft = Offset(rect.center.x - number.layout.size.width / 2f, baseline - number.layout.firstBaseline)
    }

    /**
     * Draws the marks and the number in [on] (the seat colour's on-colour; the layout
     * editor passes its own). With a [halo] colour, everything gets a rim of it
     * first, so it keeps its contrast over an unknown background (the editor's
     * reference screenshot).
     */
    fun DrawScope.draw(colors: CinexColors, on: Color, halo: Color? = null) {
        favourite?.run { drawOutline(colors) }
        for (mark in marks) {
            if (halo != null) drawGlyph(mark.glyph, mark.box, halo, mark.halo)
            drawGlyph(mark.glyph, mark.box, on, mark.stroke ?: Fill)
        }
        if (halo != null) drawText(number.layout, color = halo, topLeft = numberTopLeft, drawStyle = numberHalo)
        // Fill explicitly: without a draw style the text paint keeps the halo's stroke.
        drawText(number.layout, color = on, topLeft = numberTopLeft, drawStyle = Fill)
    }
}

/** The favourite's light outline just outside a seat, built once. */
internal class FavouriteOutline(rect: Rect) {
    private val path = Path().apply { addRoundRect(seatRoundRect(rect.inflate(rect.width * 0.09f))) }
    private val stroke = Stroke(width = rect.width * 0.08f)

    fun DrawScope.drawOutline(colors: CinexColors) = drawPath(path, colors.text, style = stroke)
}

/** The favourite's light outline just outside the seat [rect] (for single seats, e.g. the legend). */
internal fun DrawScope.drawFavouriteOutline(rect: Rect, colors: CinexColors) = with(FavouriteOutline(rect)) { drawOutline(colors) }
