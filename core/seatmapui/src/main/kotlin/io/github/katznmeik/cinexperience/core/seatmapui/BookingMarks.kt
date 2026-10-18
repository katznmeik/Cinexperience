package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/*
 * The seats of an imported ticket's booking (spec §6.10, several seats): on the map
 * a dashed rim inside the seat, in the seat's on-colour (a shape, not a colour, and
 * clear of the selection ring and the neighbouring seats); for screen readers
 * "…, in this booking".
 */

/** Appended to a seat's screen-reader description when it belongs to the booking. */
const val IN_THIS_BOOKING: String = ", in this booking"

/** Screen-reader text of the [BookingLegend]. */
const val BOOKING_LEGEND_DESCRIPTION: String = "Legend: a dashed rim marks the seats of this booking."

/** How far inside the seat's edge the rim runs, and its width, in dp at zoom 1. */
private const val BOOKING_RIM_INSET = 2.2f
private const val BOOKING_RIM_WIDTH = 1.1f

/** Dash and gap of the rim, in dp at zoom 1. */
private const val BOOKING_RIM_DASH = 2f
private const val BOOKING_RIM_GAP = 1.4f

/** The booking's dashed rim inside the seat [rect], in [color]; [dp] is px per dp. */
internal fun DrawScope.drawBookingMark(rect: Rect, color: Color, dp: Float) {
    val rim = rect.deflate(BOOKING_RIM_INSET * dp)
    if (rim.width <= 0f || rim.height <= 0f) return
    drawPath(
        Path().apply { addRoundRect(seatRoundRect(rim)) },
        color,
        style = Stroke(
            width = BOOKING_RIM_WIDTH * dp,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(BOOKING_RIM_DASH * dp, BOOKING_RIM_GAP * dp)),
        ),
    )
}

/**
 * The legend of the booking's mark under a [SeatMap] with `bookingSeatIds`: a seat
 * drawn with the dashed rim and "In this booking".
 */
@Composable
fun BookingLegend(modifier: Modifier = Modifier) {
    val colors = CinexTheme.colors
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = BOOKING_LEGEND_DESCRIPTION },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(22.dp)) {
            val inset = 1.dp.toPx()
            val rect = Rect(inset, inset, size.width - inset, size.height - inset)
            drawPath(Path().apply { addRoundRect(seatRoundRect(rect)) }, colors.seat.noData)
            drawPath(
                Path().apply { addRoundRect(seatRoundRect(rect.noDataRim(density))) },
                colors.seat.noDataOutline,
                style = Stroke(width = NO_DATA_RIM_WIDTH * density),
            )
            drawBookingMark(rect, colors.seat.onNoData, density)
        }
        Text("In this booking", style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.textMuted)
    }
}
