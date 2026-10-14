package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Number of stars of every rating (spec §4.4: 1–5). */
const val MAX_STARS: Int = 5

/**
 * Five tappable stars for an optional 1–5 rating: filled light up to [rating],
 * outlined muted after it. Tapping a star calls [onRate] with its value; the
 * caller clears the rating when the already selected star is tapped again (all
 * ratings are optional). Each star has a [touchSize] touch target and is
 * announced as "[label]: n stars".
 */
@Composable
fun StarRatingInput(
    rating: Int?,
    onRate: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    starSize: Dp = 24.dp,
    touchSize: Dp = 40.dp,
) {
    Row(modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        for (value in 1..MAX_STARS) {
            val filled = rating != null && value <= rating
            Box(
                modifier = Modifier
                    .size(touchSize)
                    .selectable(selected = value == rating, onClick = { onRate(value) }, role = Role.RadioButton)
                    .semantics { contentDescription = "$label: ${starsText(value)}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (filled) CinexIcons.Star else CinexIcons.StarOutline,
                    contentDescription = null,
                    tint = if (filled) CinexTheme.colors.text else CinexTheme.colors.textMuted,
                    modifier = Modifier.size(starSize),
                )
            }
        }
    }
}

/** Five small display-only stars for a rating; nothing is filled for `null`. */
@Composable
fun StarRatingDisplay(rating: Int?, modifier: Modifier = Modifier, starSize: Dp = 13.dp) {
    Row(
        modifier.semantics { contentDescription = rating?.let { "Rated ${starsText(it)}" } ?: "Not rated" },
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        for (value in 1..MAX_STARS) {
            val filled = rating != null && value <= rating
            Icon(
                imageVector = if (filled) CinexIcons.Star else CinexIcons.StarOutline,
                contentDescription = null,
                tint = if (filled) CinexTheme.colors.text else CinexTheme.colors.textMuted,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/** "1 star", "4 stars". */
fun starsText(count: Int): String = if (count == 1) "1 star" else "$count stars"
