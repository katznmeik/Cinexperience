package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** The entries of the [SeatLegend], left to right. */
private val legendEntries: List<Pair<String, SeatVisual>> = listOf(
    SeatTone.GREAT.label to SeatVisual(SeatTone.GREAT),
    SeatTone.OK.label to SeatVisual(SeatTone.OK),
    SeatTone.AVOID.label to SeatVisual(SeatTone.AVOID),
    SeatTone.NO_DATA.label to SeatVisual(SeatTone.NO_DATA),
    "Favourite" to SeatVisual(SeatTone.NO_DATA, isFavourite = true),
)

/** Screen-reader text of the [SeatLegend]. */
const val SEAT_LEGEND_DESCRIPTION: String =
    "Legend: green is great, yellow is OK, red with a cross is avoid, grey is no data, a star marks the favourite seat."

/**
 * Legend of the seat colours under a [SeatMap]: Great · OK · Avoid · No data ·
 * Favourite, each with a small seat swatch drawn like on the map (× on "Avoid",
 * ★ and outline on "Favourite"). Wraps on narrow screens.
 */
@Composable
fun SeatLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = SEAT_LEGEND_DESCRIPTION },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        legendEntries.forEach { (label, visual) -> LegendEntry(label, visual) }
    }
}

@Composable
private fun LegendEntry(label: String, visual: SeatVisual) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        SeatSwatch(visual)
        Text(label, style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.textMuted)
    }
}

/** One seat drawn at legend size, with its marks. */
@Composable
fun SeatSwatch(visual: SeatVisual, modifier: Modifier = Modifier) {
    val colors = CinexTheme.colors
    Canvas(modifier.size(16.dp)) {
        // Inset so the favourite outline (10 % outside the seat) stays inside the canvas.
        val inset = 2.dp.toPx()
        val rect = Rect(inset, inset, size.width - inset, size.height - inset)
        drawPath(
            Path().apply { addRoundRect(seatRoundRect(rect)) },
            colors.seat.colorOf(visual.tone),
        )
        drawSeatMarks(rect, SeatKind.STANDARD, visual, colors)
    }
}
