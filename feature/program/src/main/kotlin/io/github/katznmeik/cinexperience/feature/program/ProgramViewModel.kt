package io.github.katznmeik.cinexperience.feature.program

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.ProgramActionResult
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.program.ProgramTime
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** What the Program tab shows (spec §6.9). */
enum class ProgramMode {
    /** The stored permission, the cached program or the filters aren't read yet. */
    LOADING,

    /** First use: asks for permission to download ("Download program" / "Not now"). */
    CONSENT,

    /** "Not now" was tapped: nothing is downloaded; the consent comes back on the next open. */
    NOT_NOW,

    /** Downloading is switched off in Settings. */
    OFF,

    /** Downloads are allowed: the program (if any), its state and the filters. */
    PROGRAM,
}

/** Dialogs of the Program tab. */
enum class ProgramDialog { SAVED_FILTERS, SAVE_FILTER, FROM_TIME, TO_TIME, PICK_DATE }

/**
 * The Program tab (spec §6.9).
 *
 * @property today the Berlin date the day filters refer to.
 * @property hasProgram a program has been downloaded (it may still have no matching showings).
 * @property isRefreshing a download is running.
 * @property errorMessage why the last download failed; the last program stays.
 * @property cinemaOptions the app cinemas the cinema filter offers.
 * @property eventOptions event labels of the program (and selected ones it no longer has).
 * @property dateOptions dates offered for [DayChoice.DATES]: the next two weeks and any chosen later.
 * @property lastDate the last date of the program, for the date picker.
 * @property days the matching upcoming showings, grouped by day.
 * @property pendingLink a yorck.de link the screen should open in the browser now.
 */
data class ProgramUiState(
    val mode: ProgramMode = ProgramMode.LOADING,
    val today: LocalDate = LocalDate.MIN,
    val asOf: Instant? = null,
    val hasProgram: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val filter: ProgramFilter = ProgramFilter(),
    val cinemaOptions: List<AppCinema> = emptyList(),
    val eventOptions: List<String> = emptyList(),
    val dateOptions: List<LocalDate> = emptyList(),
    val lastDate: LocalDate? = null,
    val days: List<ShowingDay> = emptyList(),
    val savedFilters: List<SavedFilter> = emptyList(),
    val filtersExpanded: Boolean = false,
    val dialog: ProgramDialog? = null,
    val message: String? = null,
    val pendingLink: String? = null,
) {
    /** The favourite cinemas' seed keys (the "Favourites" shortcut). */
    val favouriteCinemas: Set<String> get() = cinemaOptions.filter { it.isFavourite }.mapTo(LinkedHashSet()) { it.seedKey }

    val showingCount: Int get() = days.sumOf { it.showings.size }
}

/** The parts of the state this ViewModel owns. */
private data class LocalState(
    val filtersLoaded: Boolean = false,
    val settings: ProgramFilterSettings = ProgramFilterSettings(),
    val notNow: Boolean = false,
    val filtersExpanded: Boolean = false,
    val dialog: ProgramDialog? = null,
    val message: String? = null,
    val pendingLink: String? = null,
)

/**
 * The Program tab (spec §6.9, §11.1): consent, the downloaded program with its
 * state, the filters (kept in [filterStore]) and the showing actions.
 *
 * Downloads only through [programRepository]: [onOpened] asks for a download if
 * the program is stale, pull to refresh ([refresh]) for a new one; the repository
 * downloads only when the owner has allowed it.
 *
 * @param initialCinema seed key of the cinema to filter to ("Show all in Program"
 *   on a cinema's page), applied once per screen.
 */
class ProgramViewModel(
    private val programRepository: ProgramRepository,
    cinemaRepository: CinemaRepository,
    private val filterStore: ProgramFilterStore,
    private val savedState: SavedStateHandle,
    private val initialCinema: String? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val writeLock = Mutex()

    private val cinemas = cinemaRepository.observeCinemaDetails()
        .map { CinemaJoin(it) }
        .catch { emit(CinemaJoin.EMPTY) }
        .onStart { emit(CinemaJoin.EMPTY) }

    val uiState: StateFlow<ProgramUiState> =
        combine(programRepository.state, cinemas, local) { program, join, local -> build(program, join, local) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramUiState())

    init {
        viewModelScope.launch { loadFilters() }
    }

    private suspend fun loadFilters() {
        val today = ProgramTime.now(clock).toLocalDate()
        var settings = filterStore.read()
        settings = settings.copy(current = settings.current.withoutPastDates(today))
        val cinema = initialCinema
        if (cinema != null && savedState.get<Boolean>(INITIAL_CINEMA_APPLIED) != true) {
            savedState[INITIAL_CINEMA_APPLIED] = true
            // "Show all in Program": this cinema over the next 7 days (as on its page).
            settings = settings.copy(
                current = ProgramFilter(
                    cinemas = setOf(cinema),
                    day = DayChoice.DATES,
                    dates = ProgramFilters.datesBetween(today, today.plusDays(NOW_SHOWING_DAYS - 1)),
                ),
            )
            local.update { it.copy(filtersLoaded = true, settings = settings) }
            persist()
        } else {
            local.update { it.copy(filtersLoaded = true, settings = settings) }
        }
    }

    /** The app cinemas as last shown, for "Log visit". */
    private var latestJoin: CinemaJoin = CinemaJoin.EMPTY

    private fun build(program: ProgramState, join: CinemaJoin, local: LocalState): ProgramUiState {
        latestJoin = join
        val now = clock.instant()
        val today = ProgramTime.now(clock).toLocalDate()
        val filter = local.settings.current
        val mode = when {
            !program.isLoaded || !local.filtersLoaded -> ProgramMode.LOADING
            program.permission == DownloadPermission.NOT_ASKED -> if (local.notNow) ProgramMode.NOT_NOW else ProgramMode.CONSENT
            program.permission == DownloadPermission.OFF -> ProgramMode.OFF
            else -> ProgramMode.PROGRAM
        }
        val current = program.program
        val days = if (current != null && mode == ProgramMode.PROGRAM) {
            groupByDay(current, ProgramFilters.apply(current, filter, now, today), join)
        } else {
            emptyList()
        }
        val events = (ProgramFilters.eventLabels(current, now) + filter.events)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
        val dates = (ProgramFilters.datesBetween(today, today.plusDays(DATE_OPTION_DAYS - 1)) + filter.datesFrom(today)).sorted()
        return ProgramUiState(
            mode = mode,
            today = today,
            asOf = program.fetchedAt,
            hasProgram = current != null,
            isRefreshing = program.status == ProgramStatus.Loading,
            errorMessage = downloadErrorMessage(program.status),
            filter = filter,
            cinemaOptions = join.cinemas,
            eventOptions = events,
            dateOptions = dates,
            lastDate = current?.showings?.lastOrNull()?.start?.toLocalDate(),
            days = days,
            savedFilters = local.settings.saved,
            filtersExpanded = local.filtersExpanded,
            dialog = local.dialog,
            message = local.message,
            pendingLink = local.pendingLink,
        )
    }

    private fun ProgramFilter.datesFrom(today: LocalDate): Set<LocalDate> = dates.filterTo(HashSet()) { !it.isBefore(today) }

    // ---- Downloads ----

    /**
     * The tab was opened: the consent comes back after "Not now", and a stale (or
     * missing) program is downloaded if the owner has allowed downloads.
     */
    fun onOpened() {
        local.update { it.copy(notNow = false) }
        viewModelScope.launch {
            val state = programRepository.state.first { it.isLoaded }
            if (state.permission == DownloadPermission.ALLOWED) programRepository.refreshIfStale()
        }
    }

    /** "Download program" on the consent screen. */
    fun allowDownloads() {
        viewModelScope.launch {
            report(programRepository.allowDownloads())
            programRepository.refresh()
        }
    }

    /** "Not now": nothing is downloaded. */
    fun notNow() {
        local.update { it.copy(notNow = true) }
    }

    /** "Turn on" while downloading is switched off. */
    fun turnOn() {
        viewModelScope.launch {
            report(programRepository.setDownloadsEnabled(true))
            programRepository.refreshIfStale()
        }
    }

    /** Shows why a command failed (e.g. the setting couldn't be stored; it still applies until the app closes). */
    private fun report(result: ProgramActionResult) {
        if (result is ProgramActionResult.Failure) local.update { it.copy(message = result.message) }
    }

    /** Hides the last download error (the error card's "Dismiss"). */
    fun dismissError() = programRepository.dismissError()

    /** Pull to refresh (or the refresh button): a new download. */
    fun refresh() {
        if (programRepository.state.value.permission != DownloadPermission.ALLOWED) return
        viewModelScope.launch { programRepository.refresh() }
    }

    // ---- Filters ----

    private fun editFilter(transform: (ProgramFilter) -> ProgramFilter) {
        if (!local.value.filtersLoaded) return
        local.update { it.copy(settings = it.settings.copy(current = transform(it.settings.current))) }
        persist()
    }

    /** Writes the latest settings; a later change always ends up stored last. */
    private fun persist() {
        viewModelScope.launch {
            writeLock.withLock {
                if (!filterStore.write(local.value.settings)) local.update { it.copy(message = FILTER_NOT_SAVED) }
            }
        }
    }

    fun toggleFiltersExpanded() = local.update { it.copy(filtersExpanded = !it.filtersExpanded) }

    fun toggleCinema(seedKey: String) = editFilter { f ->
        f.copy(cinemas = if (seedKey in f.cinemas) f.cinemas - seedKey else f.cinemas + seedKey)
    }

    fun showAllCinemas() = editFilter { it.copy(cinemas = emptySet()) }

    /** The "Favourites" shortcut: picks exactly the favourite cinemas. */
    fun showFavouriteCinemas() {
        val favourites = uiState.value.favouriteCinemas
        if (favourites.isEmpty()) {
            local.update { it.copy(message = NO_FAVOURITES) }
            return
        }
        editFilter { it.copy(cinemas = favourites) }
    }

    fun selectDay(day: DayChoice) = editFilter { it.copy(day = day) }

    /** Adds or removes a chosen date (and switches to chosen dates). */
    fun toggleDate(date: LocalDate) = editFilter { f ->
        f.copy(day = DayChoice.DATES, dates = if (date in f.dates) f.dates - date else (f.dates + date).toSortedSet())
    }

    /** A date picked in the date picker. */
    fun addDate(date: LocalDate) {
        local.update { it.copy(dialog = null) }
        editFilter { f -> f.copy(day = DayChoice.DATES, dates = (f.dates + date).toSortedSet()) }
    }

    fun setFrom(time: LocalTime?) {
        local.update { it.copy(dialog = null) }
        editFilter { it.copy(from = time) }
    }

    fun setTo(time: LocalTime?) {
        local.update { it.copy(dialog = null) }
        editFilter { it.copy(to = time) }
    }

    fun toggleVersion(version: ShowingVersion) = editFilter { f ->
        f.copy(versions = if (version in f.versions) f.versions - version else f.versions + version)
    }

    fun toggleEvent(label: String) = editFilter { f ->
        val selected = f.events.firstOrNull { it.equals(label, ignoreCase = true) }
        f.copy(events = if (selected != null) f.events - selected else f.events + label)
    }

    fun setSearch(text: String) = editFilter { it.copy(search = text.take(MAX_SEARCH_LENGTH)) }

    /** Back to the defaults: every cinema, today, any time and version, no search. */
    fun resetFilters() = editFilter { ProgramFilter() }

    // ---- Saved filters ----

    fun openDialog(dialog: ProgramDialog) = local.update { it.copy(dialog = dialog) }

    fun dismissDialog() = local.update { it.copy(dialog = null) }

    /**
     * Saves the current filter as [name] (replacing one with the same name).
     * Returns an error text for the name field instead, e.g. for a blank name.
     */
    fun saveFilter(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return NAME_REQUIRED
        if (trimmed.length > ProgramFilterCodec.MAX_NAME_LENGTH) return NAME_TOO_LONG
        val saved = local.value.settings.saved
        if (saved.none { it.name == trimmed } && saved.size >= ProgramFilterCodec.MAX_SAVED) return TOO_MANY
        local.update { s ->
            val entry = SavedFilter(trimmed, s.settings.current)
            val list = s.settings.saved.filterNot { it.name == trimmed } + entry
            s.copy(settings = s.settings.copy(saved = list.sortedBy { it.name.lowercase() }), dialog = null, message = "Saved “${isolate(trimmed)}”")
        }
        persist()
        return null
    }

    /** Picks a saved filter: it becomes the current filter. */
    fun applySavedFilter(name: String) {
        val entry = local.value.settings.saved.firstOrNull { it.name == name } ?: return
        val today = ProgramTime.now(clock).toLocalDate()
        local.update { it.copy(dialog = null) }
        editFilter { entry.filter.withoutPastDates(today) }
    }

    fun deleteSavedFilter(name: String) {
        local.update { s -> s.copy(settings = s.settings.copy(saved = s.settings.saved.filterNot { it.name == name })) }
        persist()
    }

    // ---- Showing actions ----

    /** "Book on yorck.de": hands the showing's link to the screen, which opens it in the browser. */
    fun book(showingId: String) {
        val link = ShowingActions.bookingLink(programRepository.state.value.program, showingId, clock.instant())
        local.update { if (link != null) it.copy(pendingLink = link) else it.copy(message = ShowingActions.NO_LINK) }
    }

    /** The screen handled [ProgramUiState.pendingLink]; [opened] is false if no browser could open it. */
    fun onLinkHandled(opened: Boolean) {
        local.update { it.copy(pendingLink = null, message = if (opened) it.message else ShowingActions.NO_BROWSER) }
    }

    /** "Log visit": the pre-fill for Add visit, `null` (with a message) if the showing is gone. */
    fun logVisit(showingId: String): LogVisitRequest? {
        val request = ShowingActions.logVisit(programRepository.state.value.program, showingId, latestJoin)
        if (request == null) local.update { it.copy(message = ShowingActions.SHOWING_GONE) }
        return request
    }

    fun messageShown() = local.update { it.copy(message = null) }

    companion object {
        /** "Now showing" and "Show all in Program" cover this many days, today included (spec §6.2). */
        const val NOW_SHOWING_DAYS = 7L
        private const val DATE_OPTION_DAYS = 14L
        private const val MAX_SEARCH_LENGTH = 100
        private const val INITIAL_CINEMA_APPLIED = "program.initialCinemaApplied"

        const val NO_FAVOURITES = "No favourite cinemas yet. Mark one with ☆ on its page."
        const val FILTER_NOT_SAVED = "The filter couldn't be saved on the phone."
        const val NAME_REQUIRED = "Enter a name."
        const val NAME_TOO_LONG = "Use at most 40 characters."
        const val TOO_MANY = "Delete a saved filter first (at most 50)."

        fun factory(
            programRepository: ProgramRepository,
            cinemaRepository: CinemaRepository,
            filterStore: ProgramFilterStore,
            initialCinema: String?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProgramViewModel(programRepository, cinemaRepository, filterStore, createSavedStateHandle(), initialCinema)
            }
        }
    }
}
