package io.github.katznmeik.cinexperience.feature.program

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.program.FilmKey
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.ui.component.ActionPillButton
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChipRow
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.ToggleChip
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.dayHeaderText
import io.github.katznmeik.cinexperience.core.ui.text.shortDayText
import io.github.katznmeik.cinexperience.core.ui.text.timeText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import java.time.LocalDate
import java.time.LocalTime

/** Callbacks of the Program tab, implemented by [ProgramViewModel] and the app's navigation. */
internal interface ProgramActions {
    fun onAllowDownloads() {}
    fun onNotNow() {}
    fun onTurnOn() {}
    fun onRefresh() {}
    fun onDismissError() {}
    fun onSearch(text: String) {}
    fun onSelectDay(day: DayChoice) {}
    fun onToggleDate(date: LocalDate) {}
    fun onAddDate(date: LocalDate) {}
    fun onToggleFilters() {}
    fun onShowAllCinemas() {}
    fun onShowFavouriteCinemas() {}
    fun onToggleCinema(seedKey: String) {}
    fun onSetFrom(time: LocalTime?) {}
    fun onSetTo(time: LocalTime?) {}
    fun onToggleVersion(version: ShowingVersion) {}
    fun onToggleEvent(label: String) {}
    fun onResetFilters() {}
    fun onOpenDialog(dialog: ProgramDialog) {}
    fun onDismissDialog() {}
    fun onConfirmReplace() {}
    fun onSaveFilter(name: String): String? = null
    fun onApplySavedFilter(name: String) {}
    fun onDeleteSavedFilter(name: String) {}
    fun onOpenFilm(film: FilmKey) {}
    fun onBook(showingId: String) {}
    fun onLogVisit(showingId: String) {}
    fun onMessageShown() {}

    companion object {
        val None = object : ProgramActions {}
    }
}

@Composable
internal fun ProgramEntry(
    viewModel: ProgramViewModel,
    onOpenFilm: (FilmKey) -> Unit,
    onLogVisit: (LogVisitRequest) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Once per open of the tab: the back stack entry is new after a tab switch, the
    // same when coming back from the film page (see ProgramViewModel.onOpened).
    val entry = LocalLifecycleOwner.current
    LaunchedEffect(entry) { viewModel.onOpened(entry) }
    OpenLinkEffect(state.pendingLink, viewModel::onLinkHandled)
    val actions = remember(viewModel) {
        object : ProgramActions {
            override fun onAllowDownloads() = viewModel.allowDownloads()
            override fun onNotNow() = viewModel.notNow()
            override fun onTurnOn() = viewModel.turnOn()
            override fun onRefresh() = viewModel.refresh()
            override fun onDismissError() = viewModel.dismissError()
            override fun onSearch(text: String) = viewModel.setSearch(text)
            override fun onSelectDay(day: DayChoice) = viewModel.selectDay(day)
            override fun onToggleDate(date: LocalDate) = viewModel.toggleDate(date)
            override fun onAddDate(date: LocalDate) = viewModel.addDate(date)
            override fun onToggleFilters() = viewModel.toggleFiltersExpanded()
            override fun onShowAllCinemas() = viewModel.showAllCinemas()
            override fun onShowFavouriteCinemas() = viewModel.showFavouriteCinemas()
            override fun onToggleCinema(seedKey: String) = viewModel.toggleCinema(seedKey)
            override fun onSetFrom(time: LocalTime?) = viewModel.setFrom(time)
            override fun onSetTo(time: LocalTime?) = viewModel.setTo(time)
            override fun onToggleVersion(version: ShowingVersion) = viewModel.toggleVersion(version)
            override fun onToggleEvent(label: String) = viewModel.toggleEvent(label)
            override fun onResetFilters() = viewModel.resetFilters()
            override fun onOpenDialog(dialog: ProgramDialog) = viewModel.openDialog(dialog)
            override fun onDismissDialog() = viewModel.dismissDialog()
            override fun onConfirmReplace() = viewModel.confirmReplace()
            override fun onSaveFilter(name: String) = viewModel.saveFilter(name)
            override fun onApplySavedFilter(name: String) = viewModel.applySavedFilter(name)
            override fun onDeleteSavedFilter(name: String) = viewModel.deleteSavedFilter(name)
            override fun onOpenFilm(film: FilmKey) = onOpenFilm(film)
            override fun onBook(showingId: String) = viewModel.book(showingId)
            override fun onLogVisit(showingId: String) {
                viewModel.logVisit(showingId)?.let(onLogVisit)
            }
            override fun onMessageShown() = viewModel.messageShown()
        }
    }
    ProgramScreen(state, actions)
}

/** The Program tab (spec §6.9). */
@Composable
internal fun ProgramScreen(state: ProgramUiState, actions: ProgramActions, modifier: Modifier = Modifier) {
    val snackbarHostState = rememberMessageSnackbarState(state.message, actions::onMessageShown)
    Box(modifier.fillMaxSize()) {
        when (state.mode) {
            ProgramMode.LOADING -> CinexScreen(title = "Program")
            ProgramMode.CONSENT -> ConsentContent(actions)
            ProgramMode.NOT_NOW -> MessageContent(
                text = "Nothing was downloaded. You can download Yorck's program here whenever you like.",
                button = "Download program",
                onClick = actions::onAllowDownloads,
            )
            ProgramMode.OFF -> if (state.hasProgram) {
                // The downloaded program stays readable; nothing is downloaded.
                ProgramList(state, actions)
            } else {
                MessageContent(text = OFF_TEXT, button = "Turn on", onClick = actions::onTurnOn)
            }
            ProgramMode.PROGRAM -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = actions::onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                ProgramList(state, actions)
            }
        }
        MessageSnackbarHost(snackbarHostState)
    }
    ProgramDialogs(state, actions)
}

@Composable
private fun ConsentContent(actions: ProgramActions) {
    CinexScreen(title = "Program", subtitle = "Yorck's showtimes, with filters") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            BodyText("To show the program, the app downloads Yorck's public program page from www.yorck.de: films, showtimes and cinemas.")
            BodyText("Nothing else is downloaded, and nothing from your data is sent: your cinemas, visits, ratings and filters stay on this phone.")
            BodyText(
                "It downloads only when you open this tab and the program is older than 6 hours, or when you pull down to refresh. " +
                    "You can switch it off in Settings at any time.",
                muted = true,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                PrimaryButton("Download program", actions::onAllowDownloads)
                SecondaryButton("Not now", actions::onNotNow)
            }
        }
    }
}

@Composable
private fun MessageContent(text: String, button: String, onClick: () -> Unit) {
    CinexScreen(title = "Program") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            BodyText(text)
            PrimaryButton(button, onClick)
        }
    }
}

@Composable
private fun BodyText(text: String, muted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) CinexTheme.colors.textMuted else CinexTheme.colors.text,
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CinexTheme.colors.text, contentColor = CinexTheme.colors.onPrimary),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CinexTheme.colors.fieldOutline),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = CinexTheme.colors.text)
    }
}

@Composable
private fun ProgramList(state: ProgramUiState, actions: ProgramActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { Header(state, actions) }
        if (state.mode == ProgramMode.OFF) {
            item(key = "off") { OffBanner(actions) }
        }
        state.errorMessage?.let { message ->
            item(key = "error") { ErrorCard(message, actions) }
        }
        item(key = "search") { SearchField(state.filter.search, actions::onSearch) }
        item(key = "days") { DayFilter(state, actions) }
        item(key = "filters") { FilterPanel(state, actions) }
        results(state, actions)
    }
}

@Composable
private fun Header(state: ProgramUiState, actions: ProgramActions) {
    Column(Modifier.padding(start = 4.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Program", Modifier.weight(1f))
            TopBarIconButton(CinexIcons.Bookmark, "Saved filters", { actions.onOpenDialog(ProgramDialog.SAVED_FILTERS) })
            if (state.mode == ProgramMode.PROGRAM) {
                TopBarIconButton(CinexIcons.Refresh, "Refresh program", actions::onRefresh)
            }
        }
        Text(
            text = when {
                state.isRefreshing -> "Downloading the program…"
                state.asOf != null -> asOfText(state.asOf)
                else -> "Not downloaded yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.textMuted,
        )
        if (state.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = CinexTheme.colors.text,
                trackColor = CinexTheme.colors.outline,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
        }
    }
}

private const val OFF_TEXT = "Downloading the program is switched off, so the app makes no network requests. " +
    "Turn it on to see Yorck's showtimes here."

/** OFF with a downloaded program: it is shown as it is, without updates. */
@Composable
private fun OffBanner(actions: ProgramActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CinexTheme.colors.surfaceRaised)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            "Downloading is switched off: this is the last downloaded program, and it isn't updated.",
            style = MaterialTheme.typography.bodyMedium,
            color = CinexTheme.colors.text,
            modifier = Modifier.padding(end = 6.dp),
        )
        ActionPillButton(text = "Turn on", description = "Turn on program downloads", onClick = actions::onTurnOn, icon = CinexIcons.Refresh)
    }
}

@Composable
private fun ErrorCard(message: String, actions: ProgramActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CinexTheme.colors.surfaceRaised)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text, modifier = Modifier.padding(end = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionPillButton(text = "Try again", description = "Try again", onClick = actions::onRefresh, icon = CinexIcons.Refresh)
            ActionPillButton(text = "Dismiss", description = "Dismiss the error", onClick = actions::onDismissError, icon = CinexIcons.Close)
        }
    }
}

/** Film search in the form's field style; the placeholder isn't read (the field's description names it). */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val style = MaterialTheme.typography.bodyMedium.copy(color = CinexTheme.colors.text, fontWeight = FontWeight.Medium)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(CinexTheme.colors.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Search films" },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CinexTheme.colors.surface)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(CinexIcons.Search, contentDescription = null, tint = CinexTheme.colors.textMuted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            "Search films",
                            style = style.copy(fontWeight = FontWeight.Normal),
                            color = CinexTheme.colors.textMuted,
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    TopBarIconButton(CinexIcons.Close, "Clear search", { onValueChange("") }, tint = CinexTheme.colors.textMuted)
                }
            }
        },
    )
}

@Composable
private fun DayFilter(state: ProgramUiState, actions: ProgramActions) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChoiceChipRow {
            DayChoice.entries.forEach { day ->
                ChoiceChip(text = day.label, selected = state.filter.day == day, onClick = { actions.onSelectDay(day) })
            }
        }
        if (state.filter.day == DayChoice.DATES) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.dateOptions.forEach { date ->
                    ToggleChip(
                        text = shortDayText(date),
                        checked = date in state.filter.dates,
                        onCheckedChange = { actions.onToggleDate(date) },
                    )
                }
                ActionPillButton(
                    text = "Other date",
                    description = "Choose another date",
                    onClick = { actions.onOpenDialog(ProgramDialog.PICK_DATE) },
                    icon = CinexIcons.Add,
                )
            }
        }
    }
}

@Composable
private fun FilterPanel(state: ProgramUiState, actions: ProgramActions) {
    val names = remember(state.cinemaChoices) { state.cinemaChoices.associate { it.key to it.name } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CinexTheme.colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = actions::onToggleFilters)
                .semantics { stateDescription = if (state.filtersExpanded) "Expanded" else "Collapsed" }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(CinexIcons.Filter, contentDescription = null, tint = CinexTheme.colors.text, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Filters", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = CinexTheme.colors.text)
                Text(filterSummary(state.filter, names), style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
            }
            Icon(
                if (state.filtersExpanded) CinexIcons.ChevronDown else CinexIcons.ChevronRight,
                contentDescription = null,
                tint = CinexTheme.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (state.filtersExpanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterGroup("Cinemas") {
                    val favourites = state.favouriteCinemas
                    ChoiceChipRow {
                        ChoiceChip("All cinemas", selected = state.filter.cinemas.isEmpty(), onClick = actions::onShowAllCinemas)
                        ChoiceChip(
                            "Favourites",
                            selected = favourites.isNotEmpty() && state.filter.cinemas == favourites,
                            onClick = actions::onShowFavouriteCinemas,
                            icon = CinexIcons.Star,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.cinemaChoices.forEach { cinema ->
                            ToggleChip(
                                text = cinema.name,
                                checked = cinema.key in state.filter.cinemas,
                                onCheckedChange = { actions.onToggleCinema(cinema.key) },
                            )
                        }
                    }
                }
                FilterGroup("Start time") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val from = state.filter.from
                        val to = state.filter.to
                        ChoiceChip("Any time", selected = from == null && to == null, onClick = {
                            actions.onSetFrom(null)
                            actions.onSetTo(null)
                        })
                        ActionPillButton(
                            text = from?.let { "From ${timeText(it)}" } ?: "From …",
                            description = from?.let { "Earliest start, ${timeText(it)}" } ?: "Earliest start, any",
                            onClick = { actions.onOpenDialog(ProgramDialog.FROM_TIME) },
                        )
                        ActionPillButton(
                            text = to?.let { "Until ${timeText(it)}" } ?: "Until …",
                            description = to?.let { "Latest start, ${timeText(it)}" } ?: "Latest start, any",
                            onClick = { actions.onOpenDialog(ProgramDialog.TO_TIME) },
                        )
                    }
                }
                FilterGroup("Version") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShowingVersion.entries.forEach { version ->
                            ToggleChip(
                                text = version.label,
                                checked = version in state.filter.versions,
                                onCheckedChange = { actions.onToggleVersion(version) },
                            )
                        }
                        state.eventOptions.forEach { label ->
                            ToggleChip(
                                text = label,
                                checked = state.filter.events.any { it.equals(label, ignoreCase = true) },
                                onCheckedChange = { actions.onToggleEvent(label) },
                            )
                        }
                    }
                    Text(
                        "DF German · OmU German subtitles · OmeU English subtitles · OV original version",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinexTheme.colors.textMuted,
                    )
                }
                if (!state.filter.isDefault) {
                    ActionPillButton(text = "Reset filters", description = "Reset filters", onClick = actions::onResetFilters, icon = CinexIcons.Undo)
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionHeader(title, Modifier.padding(top = 4.dp))
        content()
    }
}

private fun LazyListScope.results(state: ProgramUiState, actions: ProgramActions) {
    val empty = when {
        !state.hasProgram && state.isRefreshing -> "Downloading Yorck's program…"
        !state.hasProgram && state.errorMessage != null -> null
        !state.hasProgram -> "No program downloaded yet. Pull down or tap refresh to download it."
        state.days.isEmpty() && state.filter.day == DayChoice.DATES && state.filter.dates.isEmpty() -> "Choose one or more dates."
        state.days.isEmpty() -> "No upcoming showings match these filters."
        else -> null
    }
    if (empty != null) {
        item(key = "empty") {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(empty, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted)
                if (state.hasProgram && !state.filter.isDefault) {
                    ActionPillButton(text = "Reset filters", description = "Reset filters", onClick = actions::onResetFilters, icon = CinexIcons.Undo)
                }
            }
        }
        return
    }
    if (state.days.isNotEmpty()) {
        item(key = "count") {
            Text(
                showingsText(state.showingCount),
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    state.days.forEach { day ->
        item(key = "day-${day.date}") {
            SectionHeader(dayHeaderText(day.date, state.today), Modifier.padding(top = 8.dp))
        }
        items(day.showings, key = { it.showingId }) { item ->
            ShowingCard(
                item = item,
                onBook = { actions.onBook(item.showingId) },
                onLogVisit = { actions.onLogVisit(item.showingId) },
                onOpenFilm = { actions.onOpenFilm(item.film) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramDialogs(state: ProgramUiState, actions: ProgramActions) {
    when (state.dialog) {
        null -> Unit
        ProgramDialog.SAVED_FILTERS -> SavedFiltersDialog(state.savedFilters, actions)
        ProgramDialog.SAVE_FILTER -> SaveFilterDialog(actions)
        ProgramDialog.CONFIRM_REPLACE -> ReplaceFilterDialog(state.pendingReplace.orEmpty(), actions)
        ProgramDialog.FROM_TIME -> TimeDialog("Earliest start", state.filter.from, actions::onSetFrom, actions::onDismissDialog)
        ProgramDialog.TO_TIME -> TimeDialog("Latest start", state.filter.to, actions::onSetTo, actions::onDismissDialog)
        ProgramDialog.PICK_DATE -> DateDialog(state.today, state.lastDate, actions::onAddDate, actions::onDismissDialog)
    }
}
