package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.visits.AddVisitRoute
import io.github.katznmeik.cinexperience.feature.visits.VisitPrefillArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Add visit pre-filled from a showing of the program ("Log visit", spec §11.1). */
class VisitPrefillTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.store())
    private val showingDate = LocalDate.of(2026, 10, 17)

    /** International: one hall ("Saal 1"). */
    private val international = 7L

    private fun add(hallId: Long?, prefill: VisitPrefillArgs?) = VisitEditViewModel(
        VisitEditMode.Add(hallId, prefill),
        repository,
        repository,
        FakeVisitPhotoStore(),
        SavedStateHandle(),
        today = { LocalDate.of(2026, 10, 15) },
    )

    private val VisitEditViewModel.state: VisitEditUiState get() = uiState.value

    @Test
    fun selectsTheOnlyHallOfAOneHallCinema() {
        val vm = add(null, VisitPrefillArgs(international, showingDate, "The Quiet Projectionist", ScreeningFormat.GERMAN_DUB))
        with(vm.state) {
            assertEquals(VisitEditStatus.Ready, status)
            assertEquals("International", cinemaName)
            assertEquals("Saal 1", hallName)
            assertEquals(showingDate, form.date)
            assertEquals("The Quiet Projectionist", form.filmTitle)
            assertEquals(ScreeningFormat.GERMAN_DUB, form.format)
            assertTrue(canSave)
            assertFalse(hasUnsavedChanges) // the pre-fill is the starting point
        }
    }

    @Test
    fun setsTheCinemaAndTheOwnerPicksTheHall() {
        val vm = add(null, VisitPrefillArgs(SampleData.DELPHI_LUX, showingDate, "Neon Harbour", ScreeningFormat.OMU))
        with(vm.state) {
            assertEquals("delphi LUX", cinemaName)
            assertNull(hallName)
            assertEquals(7, halls.size)
            assertFalse(canSave)
        }
        vm.selectHall(SampleData.DELPHI_LUX_SAAL_1)
        assertEquals("Saal 1", vm.state.hallName)
        assertEquals("Neon Harbour", vm.state.form.filmTitle)
        assertEquals(ScreeningFormat.OMU, vm.state.form.format)
    }

    @Test
    fun withoutAKnownCinemaTheRestIsStillFilled() {
        val vm = add(null, VisitPrefillArgs(null, showingDate, "Neon Harbour", null))
        assertNull(vm.state.cinemaName)
        assertEquals(showingDate, vm.state.form.date)
        assertNull(vm.state.form.format)
        val unknown = add(null, VisitPrefillArgs(999L, showingDate, null, null))
        assertNull(unknown.state.cinemaName)
        assertEquals("", unknown.state.form.filmTitle)
    }

    @Test
    fun aHallFromTheRouteWinsOverThePrefilledCinema() {
        val vm = add(SampleData.DELPHI_LUX_SAAL_1, VisitPrefillArgs(international, showingDate, null, null))
        assertEquals("delphi LUX", vm.state.cinemaName)
        assertEquals("Saal 1", vm.state.hallName)
    }

    @Test
    fun filmTitlesAreCutByCodePointsNeverSplittingAnEmoji() {
        val emoji = "🎬" // a clapper board: two chars, one code point
        val vm = add(null, VisitPrefillArgs(null, showingDate, "A".repeat(199) + emoji + "B", null))
        assertEquals("A".repeat(199) + emoji, vm.state.form.filmTitle)

        // 200 astral characters (400 chars) are kept whole; the 201st is cut.
        vm.setFilmTitle(emoji.repeat(201))
        val title = vm.state.form.filmTitle
        assertEquals(emoji.repeat(200), title)
        assertEquals(VisitEditViewModel.MAX_FILM_LENGTH, title.codePointCount(0, title.length))
        assertTrue(Character.isSurrogatePair(title[398], title[399]))

        // One char over the old char limit, but within the code-point limit.
        vm.setFilmTitle("A".repeat(199) + emoji)
        assertEquals("A".repeat(199) + emoji, vm.state.form.filmTitle)
        assertEquals("ab", "abc".takeCodePoints(2))
        assertEquals("a$emoji", "a${emoji}b".takeCodePoints(2))
    }

    @Test
    fun routeArgumentsBecomeThePrefill() {
        val route = AddVisitRoute(cinemaId = international, date = "2026-10-17", filmTitle = "Paper Kites", format = ScreeningFormat.OMU.storageKey)
        assertEquals(VisitPrefillArgs(international, showingDate, "Paper Kites", ScreeningFormat.OMU), route.prefill())
        assertNull(AddVisitRoute(hallId = 3L).prefill())
        assertEquals(VisitPrefillArgs(), AddVisitRoute(date = "not a date", format = "NOPE").prefill())
    }
}
