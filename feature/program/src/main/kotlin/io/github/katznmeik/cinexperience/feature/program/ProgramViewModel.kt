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
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramActionResult
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.program.ProgramTime
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.ui.text.codePointLength
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import io.github.katznmeik.cinexperience.core.ui.text.takeCodePoints
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
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

    /**
     * Downloading is switched off in Settings: a banner with "Turn on", and the
     * cached program (if any) read-only, without refresh.
     */
    OFF,

    /** Downloads are allowed: the program (if any), its state and the filters. */
    PROGRAM,
}

/** Dialogs of the Program tab. */
enum class ProgramDialog { SAVED_FILTERS, SAVE_FILTER, CONFIRM_REPLACE, FROM_TIME, TO_TIME, PICK_DATE }

/** A cinema the cinema filter offers: an app cinema, or one of Yorck's the app doesn't know. */
data class CinemaChoice(val key: String, val name: String)

/**
 * The Program tab (spec §6.9).
 *
 * @property today the Berlin date the day filters refer to.
 * @property hasProgram a program has been downloaded (it may still have no matching showings).
 * @property isRefreshing a download is running.
 * @property errorMessage why the last download failed; the last program stays.
 * @property cinemaOptions the app cinemas (score, favourite).
 * @property cinemaChoices every cinema the cinema filter offers: the app's, then Yorck's unmatched ones.
 * @property eventOptions event labels of the program (and selected ones it no longer has).
 * @property dateOptions dates offered for [DayChoice.DATES]: the next two weeks and any chosen later.
 * @property lastDate the last date of the program, for the date picker.
 * @property days the matching upcoming showings, grouped by day.
 * @property isTemporaryFilter the filter is "Show all in Program"'s, not the remembered one.
 * @property pendingReplace the saved filter name "Replace …?" asks about.
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
    val cinemaChoices: List<CinemaChoice> = emptyList(),
    val eventOptions: List<String> = emptyList(),
    val dateOptions: List<LocalDate> = emptyList(),
    val lastDate: LocalDate? = null,
    val days: List<ShowingDay> = emptyList(),
    val savedFilters: List<SavedFilter> = emptyList(),
    val isTemporaryFilter: Boolean = false,
    val filtersExpanded: Boolean = false,
    val dialog: ProgramDialog? = null,
    val pendingReplace: String? = null,
    val message: String? = null,
    val pendingLink: String? = null,
) {
    /** The favourite cinemas' seed keys (the "Favourites" shortcut). */
    val favouriteCinemas: Set<String> get() = cinemaOptions.filter { it.isFavourite }.mapTo(LinkedHashSet()) { it.seedKey }

    val showingCount: Int get() = days.sumOf { it.showings.size }

    /** The program is shown, in the OFF mode read-only. */
    val showsProgram: Boolean get() = mode == ProgramMode.PROGRAM || (mode == ProgramMode.OFF && hasProgram)
}

/** The parts of the state this ViewModel owns. */
private data class LocalState(
    val filtersLoaded: Boolean = false,
    val settings: ProgramFilterSettings = ProgramFilterSettings(),
    /** "Show all in Program": shown instead of the remembered filter until the owner edits one. */
    val temporary: ProgramFilter? = null,
    val notNow: Boolean = false,
    val filtersExpanded: Boolean = false,
    val dialog: ProgramDialog? = null,
    val pendingReplace: String? = null,
    val message: String? = null,
    val pendingLink: String? = null,
) {
    val filter: ProgramFilter get() = temporary ?: settings.current
}

/** Emits now and then once a minute, so "now" stays current (started showings go, dates roll over). */
internal fun minuteTicks(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(60_000)
    }
}

/**
 * The Program tab (spec §6.9, §11.1): consent, the downloaded program with its
 * state, the filters (kept in [filterStore]) and the showing actions.
 *
 * Downloads only through [programRepository]: [onOpened] asks for a download if
 * the program is stale, once per open of the tab; pull to refresh ([refresh]) for
 * a new one; the repository downloads only when the owner has allowed it.
 *
 * @param initialCinema seed key of the cinema to show ("Show all in Program" on a
 *   cinema's page): a temporary filter for this open of the tab, applied once.
 * @param ticks re-evaluates "now" (default: every minute).
 * @param computeDispatcher where the results are built (off the main thread).
 * @param persistDelayMillis filter changes are stored after this pause (typing).
 */
class ProgramViewModel(
    private val programRepository: ProgramRepository,
    cinemaRepository: CinemaRepository,
    private val filterStore: ProgramFilterStore,
    private val savedState: SavedStateHandle,
    private val initialCinema: String? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    ticks: Flow<Unit> = minuteTicks(),
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val persistDelayMillis: Long = PERSIST_DELAY_MILLIS,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val writeLock = Mutex()
    private var persistJob: Job? = null

    /** A failed save is reported once until a save succeeds. */
    private var saveFailureReported = false

    /** The back stack entry of the last open of the tab (see [onOpened]). */
    private var lastVisit: WeakReference<Any>? = null

    private val cinemas = cinemaRepository.observeCinemaDetails()
        .map { CinemaJoin(it) }
        .catch { emit(CinemaJoin.EMPTY) }
        .onStart { emit(CinemaJoin.EMPTY) }

    /** The app cinemas as last shown, for "Log visit". */
    @Volatile
    private var latestJoin: CinemaJoin = CinemaJoin.EMPTY

    /** The search index of the current program (built once per program). */
    private var searchIndex: Pair<Program, ProgramFilters.SearchIndex>? = null

    val uiState: StateFlow<ProgramUiState> =
        combine(programRepository.state, cinemas, local, ticks) { program, join, local, _ -> build(program, join, local) }
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramUiState())

    init {
        viewModelScope.launch { loadFilters() }
    }

    private suspend fun loadFilters() {
        val today = ProgramTime.now(clock).toLocalDate()
        val stored = filterStore.read()
        val settings = stored.copy(current = stored.current.withoutPastDates(today))
        val cinema = initialCinema
        val temporary = if (cinema != null && savedState.get<Boolean>(INITIAL_CINEMA_APPLIED) != true) {
            savedState[INITIAL_CINEMA_APPLIED] = true
            // "Show all in Program": this cinema over the next 7 days (as on its page), this time only.
            ProgramFilter(
                cinemas = setOf(cinema),
                day = DayChoice.DATES,
                dates = ProgramFilters.datesBetween(today, today.plusDays(NOW_SHOWING_DAYS - 1)),
            )
        } else {
            null
        }
        local.update { it.copy(filtersLoaded = true, settings = settings, temporary = temporary) }
    }

    private fun indexFor(program: Program): ProgramFilters.SearchIndex {
        searchIndex?.let { (p, index) -> if (p === program) return index }
        return ProgramFilters.SearchIndex(program).also { searchIndex = program to it }
    }

    private fun build(program: ProgramState, join: CinemaJoin, local: LocalState): ProgramUiState {
        latestJoin = join
        val now = clock.instant()
        val today = ProgramTime.now(clock).toLocalDate()
        val filter = local.filter
        val mode = when {
            !program.isLoaded || !local.filtersLoaded -> ProgramMode.LOADING
            program.permission == DownloadPermission.NOT_ASKED -> if (local.notNow) ProgramMode.NOT_NOW else ProgramMode.CONSENT
            program.permission == DownloadPermission.OFF -> ProgramMode.OFF
            else -> ProgramMode.PROGRAM
        }
        val current = program.program
        val shown = current != null && (mode == ProgramMode.PROGRAM || mode == ProgramMode.OFF)
        val days = if (shown && current != null) {
            groupByDay(current, ProgramFilters.apply(current, filter, now, today, indexFor(current)), join)
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
            isRefreshing = mode == ProgramMode.PROGRAM && program.status == ProgramStatus.Loading,
            errorMessage = if (mode == ProgramMode.PROGRAM) downloadErrorMessage(program.status) else null,
            filter = filter,
            cinemaOptions = join.cinemas,
            cinemaChoices = cinemaChoices(join, current, filter),
            eventOptions = events,
            dateOptions = dates,
            lastDate = current?.showings?.lastOrNull()?.start?.toLocalDate(),
            days = days,
            savedFilters = local.settings.saved,
            isTemporaryFilter = local.temporary != null,
            filtersExpanded = local.filtersExpanded,
            dialog = local.dialog,
            pendingReplace = local.pendingReplace,
            message = local.message,
            pendingLink = local.pendingLink,
        )
    }

    private fun ProgramFilter.datesFrom(today: LocalDate): Set<LocalDate> = dates.filterTo(HashSet()) { !it.isBefore(today) }

    // ---- Downloads ----

    /**
     * The tab was opened ([visit] = its back stack entry). Once per open (a new
     * entry: switching to the tab, or the first composition), not when coming back
     * from the film page in the same tab: the consent comes back after "Not now",
     * the filter of "Show all in Program" makes way for the remembered one, and a
     * stale (or missing) program is downloaded if the owner has allowed downloads.
     */
    fun onOpened(visit: Any) {
        val previous = lastVisit?.get()
        if (previous === visit) return
        val firstOpen = lastVisit == null
        lastVisit = WeakReference(visit)
        local.update { it.copy(notNow = false, temporary = if (firstOpen) it.temporary else null) }
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

    /** Changes the shown filter; it becomes the remembered one (also after "Show all in Program"). */
    private fun editFilter(transform: (ProgramFilter) -> ProgramFilter) {
        if (!local.value.filtersLoaded) return
        local.update { it.copy(settings = it.settings.copy(current = transform(it.filter)), temporary = null) }
        persist()
    }

    /**
     * Stores the latest settings after a short pause, so typing a search writes
     * once; a later change always ends up stored last. A failed save is reported
     * once until a save succeeds.
     */
    private fun persist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (persistDelayMillis > 0) delay(persistDelayMillis)
            writeLock.withLock {
                val stored = filterStore.write(local.value.settings)
                if (stored) {
                    saveFailureReported = false
                } else if (!saveFailureReported) {
                    saveFailureReported = true
                    local.update { it.copy(message = FILTER_NOT_SAVED) }
                }
            }
        }
    }

    fun toggleFiltersExpanded() = local.update { it.copy(filtersExpanded = !it.filtersExpanded) }

    /** Adds or removes the cinema [key] ([CinemaChoice.key]). */
    fun toggleCinema(key: String) = editFilter { f ->
        f.copy(cinemas = if (key in f.cinemas) f.cinemas - key else f.cinemas + key)
    }

    fun showAllCinemas() = editFilter { it.copy(cinemas = emptySet()) }

    /** The "Favourites" shortcut: picks exactly the favourite cinemas. */
    fun showFavouriteCinemas() {
        val favourites = latestJoin.cinemas.filter { it.isFavourite }.mapTo(LinkedHashSet()) { it.seedKey }
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

    fun setSearch(text: String) = editFilter { it.copy(search = text.takeCodePoints(ProgramFilterCodec.MAX_SEARCH_LENGTH)) }

    /** Back to the defaults: every cinema, today, any time and version, no search. */
    fun resetFilters() = editFilter { ProgramFilter() }

    // ---- Saved filters ----

    fun openDialog(dialog: ProgramDialog) = local.update { it.copy(dialog = dialog) }

    fun dismissDialog() = local.update { it.copy(dialog = null, pendingReplace = null) }

    /**
     * Saves the shown filter as [name]. A name that is already used asks
     * "Replace …?" first ([ProgramDialog.CONFIRM_REPLACE], then [confirmReplace]).
     * Returns an error text for the name field instead, e.g. for a blank name.
     */
    fun saveFilter(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return NAME_REQUIRED
        if (trimmed.codePointLength > ProgramFilterCodec.MAX_NAME_LENGTH) return NAME_TOO_LONG
        val saved = local.value.settings.saved
        if (saved.any { it.name == trimmed }) {
            local.update { it.copy(dialog = ProgramDialog.CONFIRM_REPLACE, pendingReplace = trimmed) }
            return null
        }
        if (saved.size >= ProgramFilterCodec.MAX_SAVED) return TOO_MANY
        store(trimmed)
        return null
    }

    /** "Replace" in the dialog: the saved filter with that name gets the shown filter. */
    fun confirmReplace() {
        val name = local.value.pendingReplace ?: return
        store(name)
    }

    private fun store(name: String) {
        local.update { s ->
            val entry = SavedFilter(name, s.filter)
            val list = s.settings.saved.filterNot { it.name == name } + entry
            s.copy(
                settings = s.settings.copy(saved = list.sortedBy { it.name.lowercase() }),
                dialog = null,
                pendingReplace = null,
                message = "Saved “${isolate(name)}”",
            )
        }
        persist()
    }

    /** Picks a saved filter: it becomes the current filter. Chosen dates that have passed are dropped (with a note). */
    fun applySavedFilter(name: String) {
        val entry = local.value.settings.saved.firstOrNull { it.name == name } ?: return
        val today = ProgramTime.now(clock).toLocalDate()
        val filter = entry.filter.withoutPastDates(today)
        val allPast = entry.filter.day == DayChoice.DATES && filter.day != DayChoice.DATES
        local.update { it.copy(dialog = null, message = if (allPast) DATES_PAST else it.message) }
        editFilter { filter }
    }

    fun deleteSavedFilter(name: String) {
        local.update { s -> s.copy(settings = s.settings.copy(saved = s.settings.saved.filterNot { it.name == name })) }
        persist()
    }

    // ---- Showing actions ----

    /** "Book on yorck.de": hands the showing's link to the screen, which opens it in the browser. */
    fun book(showingId: String) {
        val booking = ShowingActions.booking(programRepository.state.value.program, showingId, clock.instant())
        local.update {
            if (booking is ShowingActions.Booking.Link) it.copy(pendingLink = booking.url) else it.copy(message = ShowingActions.messageFor(booking))
        }
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
        const val PERSIST_DELAY_MILLIS = 500L
        private const val INITIAL_CINEMA_APPLIED = "program.initialCinemaApplied"

        const val NO_FAVOURITES = "No favourite cinemas yet. Mark one with ☆ on its page."
        const val FILTER_NOT_SAVED = "The filter couldn't be saved on the phone."
        const val NAME_REQUIRED = "Enter a name."
        const val NAME_TOO_LONG = "Use at most 40 characters."
        const val TOO_MANY = "Delete a saved filter first (at most 50)."
        const val DATES_PAST = "Those dates are past; showing today."

        fun factory(
            programRepository: ProgramRepository,
            cinemaRepository: CinemaRepository,
            filterStore: ProgramFilterStore,
            initialCinema: String?,
            clock: Clock = Clock.systemDefaultZone(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProgramViewModel(programRepository, cinemaRepository, filterStore, createSavedStateHandle(), initialCinema, clock)
            }
        }
    }
}
