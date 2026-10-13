package io.github.katznmeik.cinexperience.feature.seatmap.map

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.ui.icon.SeatKindGlyphs

/*
 * Drawing primitives shared by the seat map and the layout editor. Everything is
 * drawn as vector paths in content coordinates, so it stays sharp at any zoom.
 */

/** Seat shape from the mockups: a rounded square with rounder bottom corners. */
internal fun seatRoundRect(rect: Rect): RoundRect {
    val top = CornerRadius(rect.width * 3f / 14f)
    val bottom = CornerRadius(rect.width * 5f / 14f)
    return RoundRect(rect, topLeft = top, topRight = top, bottomRight = bottom, bottomLeft = bottom)
}

/** Viewport size of the glyph path data (see [SeatKindGlyphs]). */
private const val GLYPH_VIEWPORT = 24f

private fun parse(vararg data: String): Path = Path().apply {
    data.forEach { addPath(PathParser().parsePathString(it).toPath()) }
}

/** Parsed seat kind and mark symbols (24×24 viewport). */
internal object SeatGlyphs {
    private val wheelchair by lazy { parse(*SeatKindGlyphs.Wheelchair.toTypedArray()) }
    private val companion by lazy { parse(*SeatKindGlyphs.CompanionSeat.toTypedArray()) }
    private val sofa by lazy { parse(*SeatKindGlyphs.Sofa.toTypedArray()) }

    /** Same star as `CinexIcons.Star`. */
    val star: Path by lazy { parse("M12 2l3 6.9 7.5.7-5.7 5 1.7 7.4L12 18l-6.5 4 1.7-7.4-5.7-5 7.5-.7z") }

    val cross: Path by lazy { parse("M6 6l12 12M18 6L6 18") }

    fun of(kind: SeatKind): Path? = when (kind) {
        SeatKind.STANDARD -> null
        SeatKind.WHEELCHAIR -> wheelchair
        SeatKind.COMPANION -> companion
        SeatKind.SOFA -> sofa
    }
}

/** Draws a 24×24 [glyph] stroked into the square [box]. */
internal fun DrawScope.strokeGlyph(glyph: Path, box: Rect, color: Color, strokeWidth: Float = 2.2f) {
    translate(box.left, box.top) {
        scale(box.width / GLYPH_VIEWPORT, box.height / GLYPH_VIEWPORT, pivot = Offset.Zero) {
            drawPath(glyph, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Draws a 24×24 [glyph] filled into the square [box]. */
internal fun DrawScope.fillGlyph(glyph: Path, box: Rect, color: Color) {
    translate(box.left, box.top) {
        scale(box.width / GLYPH_VIEWPORT, box.height / GLYPH_VIEWPORT, pivot = Offset.Zero) {
            drawPath(glyph, color)
        }
    }
}

/** The centred square of [fraction] × the rect's width inside [rect]. */
internal fun Rect.innerSquare(fraction: Float): Rect {
    val side = width * fraction
    return Rect(center.x - side / 2f, center.y - side / 2f, center.x + side / 2f, center.y + side / 2f)
}
