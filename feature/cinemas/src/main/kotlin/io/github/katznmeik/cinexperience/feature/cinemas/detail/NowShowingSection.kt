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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.ui.component.LabelChip
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)

/** "Today · Thursday 15 Oct", "Tomorrow · Friday 16 Oct", "Saturday 17 Oct". */
internal fun nowShowingDayText(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today · ${DAY.format(date)}"
    today.plusDays(1) -> "Tomorrow · ${DAY.format(date)}"
    else -> DAY.format(date)
}

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
            OutlinedPillButton(
                text = if (nowShowing is NowShowing.Showings) "Show all in Program" else "Open Program",
                onClick = onShowAllInProgram,
                icon = CinexIcons.Program,
            )
        }
    }
    when (nowShowing) {
        NowShowing.Hidden -> Unit
        is NowShowing.Hint -> item(key = "now-showing-hint") { HintText(nowShowing.reason.text()) }
        is NowShowing.Showings -> {
            if (nowShowing.days.isEmpty()) {
                item(key = "now-showing-none") { HintText("No showings in the next 7 days.") }
            }
            nowShowing.days.forEach { day ->
                item(key = "now-showing-${day.date}") {
                    Text(
                        nowShowingDayText(day.date, nowShowing.today),
                        style = MaterialTheme.typography.labelMedium,
                        color = CinexTheme.colors.textMuted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
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
        Text(
            TIME.format(item.start),
            style = CinexTheme.textStyles.score,
            color = CinexTheme.colors.text,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.filmTitle,
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
        Icon(CinexIcons.ChevronRight, contentDescription = null, tint = CinexTheme.colors.textMuted, modifier = Modifier.size(18.dp))
    }
}
