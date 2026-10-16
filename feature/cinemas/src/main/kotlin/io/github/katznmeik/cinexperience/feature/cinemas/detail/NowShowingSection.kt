package io.github.katznmeik.cinexperience.feature.cinemas.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramTime
import io.github.katznmeik.cinexperience.core.ui.component.LabelChip
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.dateTimeText
import io.github.katznmeik.cinexperience.core.ui.text.dayHeaderText
import io.github.katznmeik.cinexperience.core.ui.text.dayMonthText
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import io.github.katznmeik.cinexperience.core.ui.text.timeText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/**
 * "Now showing" (spec §6.2): the cinema's showings of the next 7 days from the
 * downloaded program, by day; a row opens the film page; "Show all in Program"
 * opens the Program tab filtered to this cinema. Without a program, a hint to the
 * Program tab.
 */
internal fun LazyListScope.nowShowingItems(
    nowShowing: NowShowing,
    onOpenFilm: (FilmKind, String) -> Unit,
    onShowAllInProgram: () -> Unit,
    onOpenProgram: () -> Unit,
) {
    if (nowShowing == NowShowing.Hidden) return
    item(key = "now-showing-header") {
        // Title left, button right; with large text the button wraps below the title.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader("Now showing", Modifier.padding(end = 12.dp))
            // "Show all" filters the Program tab to this cinema; the hints open it as it is.
            OutlinedPillButton(
                text = if (nowShowing is NowShowing.Showings) "Show all in Program" else "Open Program",
                onClick = if (nowShowing is NowShowing.Showings) onShowAllInProgram else onOpenProgram,
                icon = CinexIcons.Program,
            )
        }
    }
    if (nowShowing is NowShowing.Showings) {
        item(key = "now-showing-as-of") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "As of ${dateTimeText(nowShowing.asOf, ProgramTime.ZONE)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                )
                if (nowShowing.isOld) {
                    Text(
                        "Program from ${dayMonthText(nowShowing.asOf.atZone(ProgramTime.ZONE).toLocalDate())}; open the Program tab to update.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinexTheme.colors.text,
                    )
                    OutlinedPillButton(text = "Open Program", onClick = onOpenProgram, icon = CinexIcons.Program)
                }
            }
        }
    }
    when (nowShowing) {
        NowShowing.Hidden -> Unit
        is NowShowing.Hint -> item(key = "now-showing-hint") { HintText(nowShowing.reason.text()) }
        is NowShowing.Showings -> {
            if (nowShowing.days.isEmpty()) {
                item(key = "now-showing-none") {
                    HintText(if (nowShowing.isOld) "No showings in this program for the next 7 days." else "No showings in the next 7 days.")
                }
            }
            nowShowing.days.forEach { day ->
                item(key = "now-showing-${day.date}") {
                    Text(
                        dayHeaderText(day.date, nowShowing.today),
                        style = MaterialTheme.typography.labelMedium,
                        color = CinexTheme.colors.textMuted,
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, top = 6.dp)
                            .semantics { heading() },
                    )
                }
                items(day.showings, key = { "now-showing-${it.showingId}" }) { item ->
                    NowShowingRow(item, onClick = { onOpenFilm(item.filmKind, item.filmSlug) })
                }
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun NowShowingRow(item: NowShowingItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CinexTheme.colors.surface)
            .clickable(role = Role.Button, onClickLabel = "Open film", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The time beside the film; with large text above it, so the title keeps the width.
        if (isLargeText()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShowingTime(item)
                ShowingFilm(item)
            }
        } else {
            ShowingTime(item, Modifier.widthIn(min = 48.dp))
            ShowingFilm(item, Modifier.weight(1f))
        }
        Icon(CinexIcons.ChevronRight, contentDescription = null, tint = CinexTheme.colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ShowingTime(item: NowShowingItem, modifier: Modifier = Modifier) {
    Text(timeText(item.start), style = CinexTheme.textStyles.score, color = CinexTheme.colors.text, modifier = modifier)
}

/** The film title and its version and event chips. */
@Composable
private fun ShowingFilm(item: NowShowingItem, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            isolate(item.filmTitle),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = CinexTheme.colors.text,
        )
        if (item.versions.isNotEmpty() || item.events.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.versions.forEach { LabelChip(it.label, description = "${it.label}, ${it.description}") }
                item.events.forEach { LabelChip(it) }
            }
        }
    }
}
