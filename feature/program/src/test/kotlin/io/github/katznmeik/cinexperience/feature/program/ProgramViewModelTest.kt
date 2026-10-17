package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.FilmKey
import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class ProgramViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val cinemas = FakeCinexRepository(SampleData.store())
    private val store = InMemoryProgramFilterStore()

    /** Thursday 15 Oct 2026, 10:00 in Berlin; tests move it with [ticks]. */
    private val clock = MutableClock(SampleProgram.FETCHED_AT)
    private val today = LocalDate.of(2026, 10, 15)

    /** "Every minute": tests emit to re-evaluate "now". */
    private val ticks = MutableStateFlow(0)

    private fun TestScope.viewModel(
        program: FakeProgramRepository = FakeProgramRepository.withProgram(),
        initialCinema: String? = null,
        savedState: SavedStateHandle = SavedStateHandle(),
        persistDelayMillis: Long = 0,
    ): ProgramViewModel {
        val vm = ProgramViewModel(
            program,
            cinemas,
            store,
            savedState,
            initialCinema,
            clock,
            ticks = ticks.map { },
            computeDispatcher = mainDispatcherRule.dispatcher,
            persistDelayMillis = persistDelayMillis,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    /** One open of the tab (a new back stack entry each time). */
    private fun ProgramViewModel.open(): Any = Any().also { onOpened(it) }

    private val ProgramViewModel.state: ProgramUiState get() = uiState.value

    private fun ProgramUiState.rows(): List<String> =
        days.flatMap { day -> day.showings.map { "${it.start.toLocalTime()} ${it.cinemaName}" } }

    // ---- Consent, OFF, refresh ----

    @Test
    fun staysLoadingUntilTheRepositoryIsLoaded() = runTest {
        val program = FakeProgramRepository(ProgramState(isLoaded = false))
        val vm = viewModel(program)
        vm.open()
        assertEquals(ProgramMode.LOADING, vm.state.mode)
        // Not the consent screen while the permission is unknown, and nothing is asked for.
        program.state.update { it.copy(permission = DownloadPermission.ALLOWED) }
        assertEquals(ProgramMode.LOADING, vm.state.mode)
        assertTrue(program.refreshCalls.isEmpty())
        program.state.update { it.copy(isLoaded = true, permission = DownloadPermission.NOT_ASKED) }
        assertEquals(ProgramMode.CONSENT, vm.state.mode)
    }

    @Test
    fun staysLoadingUntilTheFiltersAreRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowStore = object : ProgramFilterStore {
            override suspend fun read(): ProgramFilterSettings {
                gate.await()
                return ProgramFilterSettings()
            }

            override suspend fun write(settings: ProgramFilterSettings) = true
        }
        val vm = ProgramViewModel(
            FakeProgramRepository.withProgram(), cinemas, slowStore, SavedStateHandle(), null, clock,
            ticks = ticks.map { }, computeDispatcher = mainDispatcherRule.dispatcher, persistDelayMillis = 0,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(ProgramMode.LOADING, vm.state.mode)
        gate.complete(Unit)
        assertEquals(ProgramMode.PROGRAM, vm.state.mode)
    }

    @Test
    fun firstUseDownloadsNothingUntilAllowed() = runTest {
        val program = FakeProgramRepository()
        val vm = viewModel(program)
        vm.open()
        assertEquals(ProgramMode.CONSENT, vm.state.mode)
        assertTrue(program.refreshCalls.isEmpty())
        assertEquals(0, program.downloads)

        vm.notNow()
        assertEquals(ProgramMode.NOT_NOW, vm.state.mode)
        assertEquals(DownloadPermission.NOT_ASKED, program.state.value.permission)
        // The consent screen comes back on the next open.
        vm.open()
        assertEquals(ProgramMode.CONSENT, vm.state.mode)
        assertEquals(0, program.downloads)

        vm.allowDownloads()
        assertEquals(DownloadPermission.ALLOWED, program.state.value.permission)
        assertEquals(listOf("refresh"), program.refreshCalls)
        assertEquals(ProgramMode.PROGRAM, vm.state.mode)
        assertTrue(vm.state.hasProgram)
    }

    @Test
    fun offShowsAMessageAndTurnOnAllowsAgain() = runTest {
        val program = FakeProgramRepository(ProgramState(permission = DownloadPermission.OFF, isLoaded = true))
        val vm = viewModel(program)
        vm.open()
        vm.refresh()
        assertEquals(ProgramMode.OFF, vm.state.mode)
        assertFalse(vm.state.showsProgram)
        assertTrue(program.refreshCalls.isEmpty())

        vm.turnOn()
        assertEquals(DownloadPermission.ALLOWED, program.state.value.permission)
        assertEquals(listOf("refreshIfStale"), program.refreshCalls)
        assertEquals(1, program.downloads)
        assertEquals(ProgramMode.PROGRAM, vm.state.mode)
    }

    @Test
    fun offWithADownloadedProgramShowsItReadOnly() = runTest {
        val program = FakeProgramRepository(
            ProgramState(permission = DownloadPermission.OFF, program = SampleProgram.program(), isLoaded = true),
        )
        val vm = viewModel(program)
        vm.open()
        vm.refresh()
        assertEquals(ProgramMode.OFF, vm.state.mode)
        assertTrue(vm.state.showsProgram)
        assertEquals(4, vm.state.showingCount)
        assertFalse(vm.state.isRefreshing)
        assertTrue(program.refreshCalls.isEmpty())
        assertEquals(0, program.downloads)
    }

    @Test
    fun openingRefreshesOnlyAStaleProgramAndPullAlwaysRefreshes() = runTest {
        val program = FakeProgramRepository.withProgram()
        val vm = viewModel(program)
        vm.open()
        assertEquals(listOf("refreshIfStale"), program.refreshCalls)
        assertEquals(0, program.downloads) // fresh: downloaded at 10:00

        program.now = SampleProgram.FETCHED_AT.plus(Duration.ofHours(7))
        vm.open()
        assertEquals(1, program.downloads)

        vm.refresh()
        assertEquals(listOf("refreshIfStale", "refreshIfStale", "refresh"), program.refreshCalls)
        assertEquals(2, program.downloads)
    }

    @Test
    fun showsLoadingAndDownloadErrorsButKeepsTheProgram() = runTest {
        val program = FakeProgramRepository.withProgram()
        val vm = viewModel(program)
        program.gate = kotlinx.coroutines.CompletableDeferred()
        program.nextError = ProgramError.OFFLINE
        vm.refresh()
        assertTrue(vm.state.isRefreshing)
        program.gate!!.complete(Unit)
        assertFalse(vm.state.isRefreshing)
        assertEquals(ProgramError.OFFLINE.message, vm.state.errorMessage)
        assertTrue(vm.state.hasProgram)
        assertEquals(SampleProgram.FETCHED_AT, vm.state.asOf)
        assertEquals(4, vm.state.showingCount)

        vm.dismissError()
        assertNull(vm.state.errorMessage)
        assertTrue(vm.state.hasProgram)
    }

    @Test
    fun aFailedConsentCommandIsReported() = runTest {
        val program = FakeProgramRepository()
        program.nextActionFailure = ProgramError.SETTING_NOT_SAVED
        val vm = viewModel(program)
        vm.allowDownloads()
        assertEquals(ProgramError.SETTING_NOT_SAVED.message, vm.state.message)
    }

    // ---- Results and the cinema join ----

    @Test
    fun todaysUpcomingShowingsJoinedWithTheAppsCinemas() = runTest {
        runBlocking { cinemas.setFavourite(SampleData.DELPHI_LUX, true) }
        val vm = viewModel()
        assertEquals(
            listOf("16:30 delphi LUX", "18:00 Rollberg", "20:45 Kant Kino", "21:30 Babylon Kreuzberg"),
            vm.state.rows(),
        )
        val first = vm.state.days.single().showings.first()
        assertEquals("The Quiet Projectionist", first.filmTitle)
        assertEquals(FilmKey(FilmKind.FILM, "the-quiet-projectionist"), first.film)
        assertTrue(first.cinema!!.isFavourite)
        assertNotNull(first.cinema.overallScore) // delphi LUX has rated visits in the sample data
        assertNull(first.hallName) // delphi LUX has 7 halls
        assertEquals(listOf(ShowingVersion.OMU), first.versions)
        assertEquals(LocalTime.of(18, 28), first.endsAbout!!.toLocalTime())
    }

    @Test
    fun singleHallCinemasShowTheirHallAndUnknownCinemasYorcksName() = runTest {
        val vm = viewModel()
        vm.selectDay(DayChoice.WEEKEND)
        val rows = vm.state.days.flatMap { it.showings }
        val international = rows.first { it.cinemaName == "International" }
        assertEquals("Saal 1", international.hallName)
        val capitol = rows.first { it.cinemaName == "Capitol Dahlem" }
        assertEquals("Saal 1", capitol.hallName)
        // Blauer Stern isn't among the sample cinemas: Yorck's (cleaned) name, no score.
        val blauerStern = rows.first { it.cinemaName == "Blauer Stern" }
        assertNull(blauerStern.cinema)
        assertNull(blauerStern.hallName)
        assertEquals(listOf(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18)), vm.state.days.map { it.date })
    }

    // ---- Filters ----

    @Test
    fun filtersAreRememberedInTheStore() = runTest {
        val vm = viewModel()
        vm.toggleCinema("delphi-lux")
        vm.toggleCinema("kant-kino")
        vm.selectDay(DayChoice.THIS_WEEK)
        vm.setFrom(LocalTime.of(16, 0))
        vm.setTo(LocalTime.of(17, 30))
        vm.toggleVersion(ShowingVersion.OMU)
        assertEquals(listOf("16:30 delphi LUX", "17:15 delphi LUX"), vm.state.rows())

        val stored = store.settings.current
        assertEquals(setOf("delphi-lux", "kant-kino"), stored.cinemas)
        assertEquals(DayChoice.THIS_WEEK, stored.day)
        assertEquals(LocalTime.of(17, 30), stored.to)

        // A new screen starts with the remembered filter.
        val again = viewModel()
        assertEquals(stored, again.state.filter)
        assertEquals(vm.state.rows(), again.state.rows())
    }

    @Test
    fun searchEventsAndReset() = runTest {
        val vm = viewModel()
        vm.selectDay(DayChoice.THIS_WEEK)
        vm.setSearch("neon")
        assertEquals(listOf("18:00 Rollberg", "21:30 Babylon Kreuzberg", "19:00 Blauer Stern"), vm.state.rows())
        vm.setSearch("")
        vm.toggleEvent("Kids")
        assertEquals(listOf("11:00 Filmtheater am Friedrichshain", "11:00 Capitol Dahlem"), vm.state.rows())
        assertTrue("Sneak" in vm.state.eventOptions)
        vm.toggleEvent("kids") // toggles the same label, ignoring case
        assertTrue(vm.state.filter.events.isEmpty())

        vm.resetFilters()
        assertEquals(ProgramFilter(), vm.state.filter)
    }

    @Test
    fun favouritesShortcutPicksTheFavouriteCinemas() = runTest {
        val vm = viewModel()
        vm.showFavouriteCinemas()
        assertEquals(ProgramViewModel.NO_FAVOURITES, vm.state.message)
        assertTrue(vm.state.filter.cinemas.isEmpty())
        vm.messageShown()

        runBlocking {
            cinemas.setFavourite(SampleData.DELPHI_LUX, true)
            cinemas.setFavourite(SampleData.BABYLON, true)
        }
        vm.showFavouriteCinemas()
        assertEquals(setOf("delphi-lux", "babylon-kreuzberg"), vm.state.filter.cinemas)
        assertEquals(listOf("16:30 delphi LUX", "21:30 Babylon Kreuzberg"), vm.state.rows())
        vm.showAllCinemas()
        assertTrue(vm.state.filter.cinemas.isEmpty())
    }

    @Test
    fun chosenDates() = runTest {
        val vm = viewModel()
        vm.toggleDate(LocalDate.of(2026, 10, 19))
        assertEquals(DayChoice.DATES, vm.state.filter.day)
        assertEquals(listOf("22:00 Odeon"), vm.state.rows())
        vm.addDate(LocalDate.of(2026, 10, 18))
        assertEquals(listOf("11:00 Capitol Dahlem", "19:00 Blauer Stern", "22:00 Odeon"), vm.state.rows())
        assertEquals(14, vm.state.dateOptions.size)
        assertEquals(LocalDate.of(2026, 10, 19), vm.state.lastDate)
    }

    @Test
    fun pastChosenDatesAreDroppedWhenLoaded() = runTest {
        store.settings = ProgramFilterSettings(ProgramFilter(day = DayChoice.DATES, dates = setOf(LocalDate.of(2026, 10, 1))))
        val vm = viewModel()
        assertEquals(ProgramFilter(), vm.state.filter)
    }

    @Test
    fun showAllInProgramIsATemporaryFilterForThisOpen() = runTest {
        val remembered = ProgramFilter(search = "neon")
        store.settings = ProgramFilterSettings(remembered)
        val saved = SavedStateHandle()
        val vm = viewModel(initialCinema = "delphi-lux", savedState = saved)
        val entry = vm.open()
        assertTrue(vm.state.isTemporaryFilter)
        assertEquals(setOf("delphi-lux"), vm.state.filter.cinemas)
        assertEquals(DayChoice.DATES, vm.state.filter.day)
        assertEquals((0L..6L).map { today.plusDays(it) }.toSet(), vm.state.filter.dates)
        assertEquals("", vm.state.filter.search)
        assertEquals(listOf("16:30 delphi LUX", "17:15 delphi LUX"), vm.state.rows())
        // Not stored: the remembered filter is untouched.
        assertEquals(remembered, store.settings.current)
        assertEquals(0, store.writes)

        // Back from the film page (same entry): still the temporary filter.
        vm.onOpened(entry)
        assertTrue(vm.state.isTemporaryFilter)

        // Opening the tab later: the remembered filter again.
        vm.open()
        assertFalse(vm.state.isTemporaryFilter)
        assertEquals(remembered, vm.state.filter)

        // Recreated with the same saved state (e.g. process death): not applied again.
        val again = viewModel(initialCinema = "delphi-lux", savedState = saved)
        assertEquals(remembered, again.state.filter)
    }

    @Test
    fun editingTheTemporaryFilterMakesItTheRememberedOne() = runTest {
        store.settings = ProgramFilterSettings(ProgramFilter(search = "neon"))
        val vm = viewModel(initialCinema = "delphi-lux")
        vm.open()
        vm.toggleCinema("kant-kino")
        assertFalse(vm.state.isTemporaryFilter)
        assertEquals(setOf("delphi-lux", "kant-kino"), store.settings.current.cinemas)
        assertEquals(DayChoice.DATES, store.settings.current.day)
        vm.open()
        assertEquals(setOf("delphi-lux", "kant-kino"), vm.state.filter.cinemas)
    }

    @Test
    fun onePossibleDownloadPerOpenNotPerReturnFromTheFilmPage() = runTest {
        val program = FakeProgramRepository.withProgram()
        val vm = viewModel(program)
        val entry = vm.open()
        vm.onOpened(entry) // back from the film page: the same back stack entry
        vm.onOpened(entry)
        assertEquals(listOf("refreshIfStale"), program.refreshCalls)
        vm.open() // switched away and back: a new entry
        assertEquals(listOf("refreshIfStale", "refreshIfStale"), program.refreshCalls)
    }

    @Test
    fun nowStaysCurrent() = runTest {
        val vm = viewModel()
        assertEquals(4, vm.state.showingCount)
        // 17:00 in Berlin: the 16:30 showing has started.
        clock.instant = Instant.parse("2026-10-15T15:00:00Z")
        ticks.value++
        assertEquals(listOf("18:00 Rollberg", "20:45 Kant Kino", "21:30 Babylon Kreuzberg"), vm.state.rows())
        // After midnight "Today" is Friday.
        clock.instant = Instant.parse("2026-10-15T22:30:00Z")
        ticks.value++
        assertEquals(LocalDate.of(2026, 10, 16), vm.state.today)
        assertEquals(listOf("17:15 delphi LUX"), vm.state.rows())
    }

    @Test
    fun bookingAShowingThatHasStartedSaysSo() = runTest {
        val vm = viewModel()
        val first = vm.state.days.single().showings.first()
        clock.instant = Instant.parse("2026-10-15T15:00:00Z")
        vm.book(first.showingId)
        assertNull(vm.state.pendingLink)
        assertEquals(ShowingActions.ALREADY_STARTED, vm.state.message)
    }

    @Test
    fun yorckCinemasWithoutAnAppCinemaCanBeFiltered() = runTest {
        val vm = viewModel()
        val choices = vm.state.cinemaChoices
        // Blauer Stern isn't among the sample app cinemas: offered by Yorck's name after the app's.
        assertEquals(CinemaChoice("blauer-stern", "Blauer Stern"), choices.last())
        assertTrue(choices.any { it.key == "delphi-lux" && it.name == "delphi LUX" })
        vm.selectDay(DayChoice.THIS_WEEK)
        vm.toggleCinema("blauer-stern")
        assertEquals(listOf("19:00 Blauer Stern"), vm.state.rows())
    }

    @Test
    fun searchIsStoredOnceAfterTypingAndAFailedSaveIsReportedOnce() = runTest {
        val vm = viewModel(persistDelayMillis = 500)
        "neon".forEachIndexed { i, _ -> vm.setSearch("neon".take(i + 1)) }
        assertEquals("neon", vm.state.filter.search)
        assertEquals(0, store.writes) // the Main test dispatcher's clock hasn't moved

        val failing = viewModel()
        store.failWrites = true
        failing.setSearch("a")
        assertEquals(ProgramViewModel.FILTER_NOT_SAVED, failing.state.message)
        failing.messageShown()
        failing.setSearch("ab")
        assertNull(failing.state.message) // not again until a save works
        store.failWrites = false
        failing.setSearch("abc")
        store.failWrites = true
        failing.setSearch("abcd")
        assertEquals(ProgramViewModel.FILTER_NOT_SAVED, failing.state.message)
    }

    // ---- Saved filters ----

    @Test
    fun saveApplyAndDeleteFilters() = runTest {
        val vm = viewModel()
        assertEquals(ProgramViewModel.NAME_REQUIRED, vm.saveFilter("  "))
        vm.toggleVersion(ShowingVersion.OV)
        vm.setFrom(LocalTime.of(20, 0))
        assertNull(vm.saveFilter("  Late OV "))
        assertEquals(listOf("Late OV"), vm.state.savedFilters.map { it.name })
        assertEquals(listOf("Late OV"), store.settings.saved.map { it.name })

        vm.resetFilters()
        vm.applySavedFilter("Late OV")
        assertEquals(setOf(ShowingVersion.OV), vm.state.filter.versions)
        assertEquals(listOf("20:45 Kant Kino"), vm.state.rows())

        // Saving under the same name asks first, then replaces it.
        vm.toggleVersion(ShowingVersion.OMU)
        assertNull(vm.saveFilter("Late OV"))
        assertEquals(ProgramDialog.CONFIRM_REPLACE, vm.state.dialog)
        assertEquals("Late OV", vm.state.pendingReplace)
        assertEquals(setOf(ShowingVersion.OV), vm.state.savedFilters.single().filter.versions)
        vm.confirmReplace()
        assertNull(vm.state.dialog)
        assertEquals(1, vm.state.savedFilters.size)
        assertEquals(setOf(ShowingVersion.OV, ShowingVersion.OMU), vm.state.savedFilters.single().filter.versions)

        vm.deleteSavedFilter("Late OV")
        assertTrue(vm.state.savedFilters.isEmpty())
        assertTrue(store.settings.saved.isEmpty())
    }

    @Test
    fun namesAreCountedInCodePoints() = runTest {
        val vm = viewModel()
        val emoji = "\uD83C\uDFAC"
        assertNull(vm.saveFilter(emoji.repeat(40))) // 80 chars, 40 code points
        assertEquals(ProgramViewModel.NAME_TOO_LONG, vm.saveFilter(emoji.repeat(41)))
        vm.setSearch(emoji.repeat(101))
        assertEquals(emoji.repeat(100), vm.state.filter.search)
    }

    @Test
    fun aSavedFilterWhoseDatesHavePassedSaysSo() = runTest {
        store.settings = ProgramFilterSettings(
            saved = listOf(SavedFilter("Last week", ProgramFilter(day = DayChoice.DATES, dates = setOf(LocalDate.of(2026, 10, 1))))),
        )
        val vm = viewModel()
        vm.applySavedFilter("Last week")
        assertEquals(DayChoice.TODAY, vm.state.filter.day)
        assertEquals(ProgramViewModel.DATES_PAST, vm.state.message)
    }

    // ---- Showing actions ----

    @Test
    fun bookHandsYorcksSeatSelectionToTheBrowser() = runTest {
        val vm = viewModel()
        val showing = vm.state.days.single().showings.first()
        vm.book(showing.showingId)
        assertEquals("https://www.yorck.de/en/checkout/seats?sessionid=${showing.showingId}", vm.state.pendingLink)
        vm.onLinkHandled(opened = true)
        assertNull(vm.state.pendingLink)
        assertNull(vm.state.message)

        vm.book(showing.showingId)
        vm.onLinkHandled(opened = false)
        assertEquals(ShowingActions.NO_BROWSER, vm.state.message)
    }

    @Test
    fun noLinkForAStartedOrUnknownShowing() = runTest {
        val vm = viewModel()
        vm.book("9999-1")
        assertNull(vm.state.pendingLink)
        assertEquals(ShowingActions.NO_LINK, vm.state.message)
    }

    @Test
    fun logVisitPrefillsCinemaDateFilmAndFormat() = runTest {
        val vm = viewModel()
        vm.selectDay(DayChoice.THIS_WEEK)
        val rows = vm.state.days.flatMap { it.showings }

        val delphi = rows.first { it.cinemaName == "delphi LUX" }
        assertEquals(
            LogVisitRequest(SampleData.DELPHI_LUX, today, "The Quiet Projectionist", ScreeningFormat.OMU),
            vm.logVisit(delphi.showingId),
        )
        val international = rows.first { it.cinemaName == "International" }
        assertEquals(
            LogVisitRequest(7L, LocalDate.of(2026, 10, 17), "The Quiet Projectionist", ScreeningFormat.GERMAN_DUB),
            vm.logVisit(international.showingId),
        )
        // OmeU has no screening format; Blauer Stern no app cinema.
        val rollberg = rows.first { it.cinemaName == "Rollberg" }
        assertNull(vm.logVisit(rollberg.showingId)!!.format)
        val blauerStern = rows.first { it.cinemaName == "Blauer Stern" }
        assertNull(vm.logVisit(blauerStern.showingId)!!.cinemaId)

        assertNull(vm.logVisit("9999-1"))
        assertEquals(ShowingActions.SHOWING_GONE, vm.state.message)
    }
}

/** A clock tests can move. */
internal class MutableClock(var instant: Instant) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this

    override fun instant(): Instant = instant
}
