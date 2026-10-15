package io.github.katznmeik.cinexperience.feature.program

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.ui.component.ActionPillButton
import io.github.katznmeik.cinexperience.core.ui.component.BackTopBar
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Callbacks of the film page. */
internal interface FilmActions {
    fun onBack() {}
    fun onOpenPage() {}
    fun onBook(showingId: String) {}
    fun onLogVisit(showingId: String) {}
    fun onMessageShown() {}

    companion object {
        val None = object : FilmActions {}
    }
}

@Composable
internal fun FilmEntry(viewModel: FilmViewModel, onBack: () -> Unit, onLogVisit: (LogVisitRequest) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OpenLinkEffect(state.pendingLink, viewModel::onLinkHandled)
    val actions = remember(viewModel) {
        object : FilmActions {
            override fun onBack() = onBack()
            override fun onOpenPage() = viewModel.openPage()
            override fun onBook(showingId: String) = viewModel.book(showingId)
            override fun onLogVisit(showingId: String) {
                viewModel.logVisit(showingId)?.let(onLogVisit)
            }
            override fun onMessageShown() = viewModel.messageShown()
        }
    }
    FilmScreen(state, actions)
}

/** The film page (spec §6.9). */
@Composable
internal fun FilmScreen(state: FilmUiState, actions: FilmActions, modifier: Modifier = Modifier) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.onMessageShown()
        }
    }
    Box(modifier.fillMaxSize()) {
        when (val content = state.content) {
            FilmContent.Loading -> BackTopBar(actions::onBack)
            FilmContent.NotFound -> Column {
                BackTopBar(actions::onBack)
                Text(
                    "This film is no longer in the downloaded program.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            is FilmContent.Loaded -> FilmContentList(content, state, actions)
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun FilmContentList(content: FilmContent.Loaded, state: FilmUiState, actions: FilmActions) {
    val film = content.film
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "top") { BackTopBar(actions::onBack) }
        item(key = "title") {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (film.kind == FilmKind.SPECIAL) {
                    Text("Special", style = CinexTheme.textStyles.sectionHeader, color = CinexTheme.colors.textMuted)
                }
                ScreenTitle(film.title)
                film.tagline?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
                }
                filmFactsText(film)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
                }
                ActionPillButton(
                    text = "Open on yorck.de",
                    description = "Open ${isolate(film.title)} on yorck.de",
                    onClick = actions::onOpenPage,
                    icon = CinexIcons.External,
                )
            }
        }
        if (content.cinemas.isEmpty()) {
            item(key = "none") {
                Text(
                    "No upcoming showings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        content.cinemas.forEach { group ->
            item(key = "cinema-${group.cinemaName}") { CinemaHeader(group) }
            group.days.forEach { day ->
                item(key = "day-${group.cinemaName}-${day.date}") {
                    Text(
                        dayHeaderText(day.date, state.today),
                        style = MaterialTheme.typography.labelMedium,
                        color = CinexTheme.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(day.showings, key = { it.showingId }) { item ->
                    ShowingCard(
                        item = item,
                        onBook = { actions.onBook(item.showingId) },
                        onLogVisit = { actions.onLogVisit(item.showingId) },
                        showFilmTitle = false,
                        showCinema = false,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/** Cinema heading with ★ for a favourite and the overall score. */
@Composable
private fun CinemaHeader(group: FilmCinemaGroup) {
    val cinema = group.cinema
    val spoken = listOfNotNull(isolate(group.cinemaName), "favourite".takeIf { cinema?.isFavourite == true }, cinema?.let(::spokenCinemaScore))
        .joinToString(", ")
    Row(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                heading()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            group.cinemaName.uppercase(),
            style = CinexTheme.textStyles.cardTitle,
            color = CinexTheme.colors.text,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (cinema?.isFavourite == true) {
            Icon(
                CinexIcons.Star,
                contentDescription = null,
                tint = CinexTheme.colors.text,
                modifier = Modifier.size(with(LocalDensity.current) { 14.sp.toDp() }),
            )
        }
        if (cinema != null) {
            Text(
                formatScore(cinema.overallScore),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CinexTheme.colors.textMuted,
            )
        }
    }
}
