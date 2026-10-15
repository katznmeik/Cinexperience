package io.github.katznmeik.cinexperience.feature.program

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.katznmeik.cinexperience.core.ui.component.ActionPillButton
import io.github.katznmeik.cinexperience.core.ui.component.LabelChip
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Only links to yorck.de are opened; [io.github.katznmeik.cinexperience.core.program.ProgramLinks] builds them. */
private const val YORCK_PREFIX = "https://www.yorck.de/"

/**
 * Opens a yorck.de link in the phone's browser (never inside the app, spec
 * §11.1); returns false if no browser can open it.
 */
@Composable
internal fun rememberBrowserOpener(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { link ->
            if (!link.startsWith(YORCK_PREFIX)) {
                false
            } else {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, link.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                    context.startActivity(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
        }
    }
}

/** Opens [link] (if any) in the browser, then reports whether it worked through [onHandled]. */
@Composable
internal fun OpenLinkEffect(link: String?, onHandled: (opened: Boolean) -> Unit) {
    val open = rememberBrowserOpener()
    LaunchedEffect(link) {
        if (link != null) onHandled(open(link))
    }
}

/**
 * One showing (spec §6.9, Results): time, film title, cinema with score and ★,
 * version and event chips, runtime with "ends about", and the hall of a one-hall
 * cinema; below it "Book on yorck.de" and "Log visit". Tapping the upper part
 * opens the film page ([onOpenFilm], `null` on the film page itself).
 */
@Composable
internal fun ShowingCard(
    item: ShowingItem,
    onBook: () -> Unit,
    onLogVisit: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFilm: (() -> Unit)? = null,
    showFilmTitle: Boolean = true,
    showCinema: Boolean = true,
    dayInDescription: Boolean = true,
) {
    val oneColumn = LocalDensity.current.fontScale > 1.3f
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CinexTheme.colors.surface),
    ) {
        val info = Modifier
            .fillMaxWidth()
            .then(if (onOpenFilm != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open film", onClick = onOpenFilm) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
        if (oneColumn) {
            Column(info, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TimeText(item)
                ShowingDetails(item, showFilmTitle, showCinema)
            }
        } else {
            Row(info, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TimeText(item, Modifier.widthIn(min = 52.dp))
                ShowingDetails(item, showFilmTitle, showCinema, Modifier.weight(1f))
            }
        }
        val spoken = if (dayInDescription) showingSpokenName(item) else "${timeText(item.start)}, ${isolate(item.cinemaName)}"
        FlowRow(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ActionPillButton(
                text = "Book on yorck.de",
                description = "Book on yorck.de: $spoken",
                onClick = onBook,
                icon = CinexIcons.External,
            )
            ActionPillButton(
                text = "Log visit",
                description = "Log visit: $spoken",
                onClick = onLogVisit,
                icon = CinexIcons.Add,
            )
        }
    }
}

@Composable
private fun TimeText(item: ShowingItem, modifier: Modifier = Modifier) {
    Text(timeText(item.start), style = CinexTheme.textStyles.score, color = CinexTheme.colors.text, modifier = modifier)
}

@Composable
private fun ShowingDetails(item: ShowingItem, showFilmTitle: Boolean, showCinema: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showFilmTitle) {
            Text(
                item.filmTitle,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = CinexTheme.colors.text,
            )
        }
        if (showCinema) CinemaLine(item)
        val hasChips = item.versions.isNotEmpty() || item.events.isNotEmpty()
        if (hasChips) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.versions.forEach { LabelChip(it.label, description = versionDescription(it)) }
                item.events.forEach { LabelChip(it) }
            }
        }
        if (item.partOf.isNotEmpty()) {
            Text(partOfText(item.partOf), style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
        val meta = listOfNotNull(runtimeText(item), item.hallName).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
    }
}

/** "delphi LUX ★ · 4.4": the cinema, ★ for a favourite and its overall score (none for cinemas the app doesn't know). */
@Composable
private fun CinemaLine(item: ShowingItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(item.cinemaName, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text, modifier = Modifier.weight(1f, fill = false))
        val cinema = item.cinema
        if (cinema?.isFavourite == true) {
            Icon(
                CinexIcons.Star,
                contentDescription = "Favourite",
                tint = CinexTheme.colors.text,
                modifier = Modifier.size(with(LocalDensity.current) { 13.sp.toDp() }),
            )
        }
        if (cinema != null) {
            Text(
                "· ${cinemaScoreText(item)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CinexTheme.colors.textMuted,
                modifier = Modifier.clearAndSetSemantics { contentDescription = spokenCinemaScore(cinema) },
            )
        }
    }
}
