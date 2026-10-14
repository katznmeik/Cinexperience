package io.github.katznmeik.cinexperience.feature.seatmap.detail

import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.seatmapui.SeatTone
import io.github.katznmeik.cinexperience.core.seatmapui.SeatVisual
import io.github.katznmeik.cinexperience.feature.seatmap.RatedHallFixture
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class HallDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var repository = FakeCinexRepository(SampleData.storeWithSplitHall())

    private fun kotlinx.coroutines.test.TestScope.loaded(hallId: Long): Pair<HallDetailViewModel, () -> HallDetailUiState.Loaded> {
        val vm = HallDetailViewModel(hallId, repository, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm to { vm.uiState.value as HallDetailUiState.Loaded }
    }

    @Test
    fun showsTheHallWithItsLayout() = runTest {
        val (_, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        with(state()) {
            assertEquals("Saal 1", hallName)
            assertEquals("delphi LUX", cinemaName)
            assertEquals(SampleLayouts.splitHall, layout)
            assertEquals(16f, screenWidth)
            assertEquals(4, visitCount)
            assertFalse(layoutUnreadable)
            assertEquals("delphi LUX · 183 seats · 4 visits", hallMetaText(this))
            assertNull(selectedSeat)
        }
    }

    @Test
    fun theScreenOffsetReachesTheMap() = runTest {
        assertEquals(0f, loaded(SampleData.DELPHI_LUX_SAAL_1).second().screenOffset)
        repository.store.value = repository.store.value.let { s ->
            s.copy(halls = s.halls.map { if (it.id == SampleData.DELPHI_LUX_SAAL_1) it.copy(screenOffset = -2.5f) else it })
        }
        assertEquals(-2.5f, loaded(SampleData.DELPHI_LUX_SAAL_1).second().screenOffset)
    }

    @Test
    fun tappingASeatSelectsItAndTappingAgainClearsIt() = runTest {
        val (vm, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        val seat = SampleLayouts.splitHall.rows[6].seatIds[8] // row 7, centre block
        vm.onSeatClick(seat)
        val selected = assertNotNullSeat(state().selectedSeat)
        assertEquals(SeatLabel("7", 5), selected.label)
        assertNotNull(selected.viewingAngle) // the hall has screen width and distance
        vm.onSeatClick(seat)
        assertNull(state().selectedSeat)
        vm.selectSeat(seat)
        vm.selectSeat(seat) // choosing from the picker never toggles
        assertEquals(seat, state().selectedSeat?.seatId)
        vm.clearSelection()
        assertNull(state().selectedSeat)
    }

    private fun assertNotNullSeat(seat: SelectedSeat?): SelectedSeat {
        assertNotNull(seat)
        return seat!!
    }

    @Test
    fun hallWithoutLayoutOrScreenGeometry() = runTest {
        val (vm, state) = loaded(SampleData.DELPHI_LUX_SAAL_6)
        assertNull(state().layout)
        assertEquals("delphi LUX · no visits", hallMetaText(state()))
        vm.onSeatClick(SeatId(1)) // no layout: nothing to select
        assertNull(state().selectedSeat)

        val (other, otherState) = loaded(502)
        val seat = SampleData.halls.single { it.id == 502L }.layout!!.seatIds.first()
        other.onSeatClick(seat)
        assertNull(otherState().selectedSeat!!.viewingAngle) // no screen geometry stored
    }

    @Test
    fun unreadableLayoutsAreFlagged() = runTest {
        repository.store.value = repository.store.value.copy(
            halls = repository.store.value.halls.map {
                if (it.id == SampleData.DELPHI_LUX_SAAL_6) it.copy(layoutUnreadable = true) else it
            },
        )
        val (_, state) = loaded(SampleData.DELPHI_LUX_SAAL_6)
        assertTrue(state().layoutUnreadable)
        assertNull(state().layout)
    }

    @Test
    fun listsTheVisitsNewestFirstWithSeatLabels() = runTest {
        val seat = SampleLayouts.splitHall.rows[6].seatIds[13] // row 7, seat 10
        val newest = Visit(
            id = 800,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 10, 3),
            filmTitle = "Perfect Days",
            seatId = seat,
            screeningFormat = ScreeningFormat.OMU,
            seatRating = Rating(4),
        )
        val removedSeat = Visit(id = 801, hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 10, 2), seatId = SeatId(9_999))
        repository.store.value = repository.store.value.copy(visits = repository.store.value.visits + removedSeat + newest)
        val (_, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        val visits = state().visits
        assertEquals(6, visits.size)
        assertEquals(listOf(800L, 801L), visits.take(2).map { it.visitId })
        assertEquals(visits.sortedByDescending { it.date }.map { it.date }, visits.map { it.date })
        with(visits[0]) {
            assertEquals(SeatLabel("7", 10), seatLabel)
            assertEquals("3 Oct 2026 · Row 7 · Seat 10 · OmU", detailText())
        }
        assertNull(visits[1].seatLabel) // its seat is no longer in the layout
        assertEquals("2 Oct 2026", visits[1].detailText())
    }

    @Test
    fun missingHallAndErrors() = runTest {
        val missing = HallDetailViewModel(999, repository, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { missing.uiState.collect {} }
        assertEquals(HallDetailUiState.NotFound, missing.uiState.value)

        repository.observeFailure = IllegalStateException("db")
        val failing = HallDetailViewModel(SampleData.DELPHI_LUX_SAAL_1, repository, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { failing.uiState.collect {} }
        assertEquals(HallDetailUiState.Error(HallDetailViewModel.LOAD_FAILED), failing.uiState.value)
    }

    // Scores (spec §5)

    @Test
    fun scoresOfEveryCategoryWithCounts() = runTest {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val (_, state) = loaded(RatedHallFixture.HALL)
        with(state()) {
            assertEquals("4.1", formatScore(overallScore))
            assertEquals(HallCategory.entries, scores.map { it.category })
            assertEquals(listOf("4.8", "4.3", "4.8", "3.8", "3.3", "3.7"), scores.map { formatScore(it.score) })
            assertEquals(listOf(4, 4, 4, 4, 4, 3), scores.map { it.count })
            assertEquals("Steepness", scores.last().label)
            assertEquals("Steepness: 3.7 from 3 ratings", scores.last().description())
        }
    }

    @Test
    fun unratedCategoriesAreDashesNotZero() = runTest {
        // Saal 6 has no visits: every category and the overall score are unrated.
        val (_, empty) = loaded(SampleData.DELPHI_LUX_SAAL_6)
        assertNull(empty().overallScore)
        assertTrue(empty().scores.all { it.score == null && it.count == 0 })
        assertEquals("–", formatScore(empty().scores.first().score))
        assertEquals("Sound: not rated", empty().scores.first().description())

        // One visit that only rates the sound: only that category (and overall) has data.
        val visit = Visit(id = 900, hallId = SampleData.DELPHI_LUX_SAAL_6, date = LocalDate.of(2026, 10, 1), hallRatings = HallRatings(sound = Rating(3)))
        repository.store.value = repository.store.value.copy(visits = repository.store.value.visits + visit)
        with(empty()) {
            assertEquals(3.0, overallScore)
            assertEquals(CategoryScoreRow(HallCategory.SOUND, 3.0, 1), scores.first())
            assertTrue(scores.drop(1).all { it.score == null && it.count == 0 })
        }
    }

    // Seat colours and the selected seat (spec §5, §6.3)

    @Test
    fun seatVisualsFollowOverridesThenRatingsThenNoData() = runTest {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val (_, state) = loaded(RatedHallFixture.HALL)
        val visuals = state().seatVisuals
        assertEquals(SampleLayouts.splitHall.seatCount, visuals.size)
        assertEquals(SeatVisual(SeatTone.GREAT, isFavourite = true), visuals[RatedHallFixture.favourite])
        assertEquals(SeatTone.AVOID, visuals.getValue(RatedHallFixture.ratedAvoid).tone)
        assertEquals(SeatTone.OK, visuals.getValue(RatedHallFixture.ratedOk).tone)
        assertEquals(SeatTone.AVOID, visuals.getValue(RatedHallFixture.manualAvoid).tone)
        assertEquals(SeatVisual.Neutral, visuals[RatedHallFixture.noData])
        assertEquals(1, visuals.values.count { it.isFavourite })
    }

    @Test
    fun selectedSeatShowsRatingVisitsAngleAndState() = runTest {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val (vm, state) = loaded(RatedHallFixture.HALL)
        vm.onSeatClick(RatedHallFixture.favourite)
        with(state().selectedSeat!!) {
            assertEquals("ROW 7 · SEAT 10", titleText())
            assertEquals(14.0 / 3, averageRating!!, 1e-9)
            assertEquals(3, visitCount)
            assertTrue(isFavourite)
            assertNull(overrideColour) // automatic: great from its ratings
            assertEquals(SeatTone.GREAT, visual.tone)
            assertTrue(detailText()!!.startsWith("★ 4.7 seat rating · 3 visits · ≈ "))
            assertTrue(detailText()!!.endsWith("° viewing angle"))
        }

        vm.onSeatClick(RatedHallFixture.noData)
        with(state().selectedSeat!!) {
            assertNull(averageRating)
            assertEquals(0, visitCount)
            assertEquals(SeatVisual.Neutral, visual)
            assertTrue(detailText()!!.startsWith("≈ ")) // only the viewing angle is known
        }

        // No screen geometry and no data: nothing to show but the label.
        val (other, otherState) = loaded(502)
        other.onSeatClick(SampleData.halls.single { it.id == 502L }.layout!!.seatIds.first())
        assertNull(otherState().selectedSeat!!.detailText())
    }

    @Test
    fun colourOverrideSetAndClear() = runTest {
        repository = FakeCinexRepository(RatedHallFixture.store())
        val (vm, state) = loaded(RatedHallFixture.HALL)
        val seat = RatedHallFixture.ratedOk
        vm.onSeatClick(seat)
        assertNull(state().selectedSeat!!.overrideColour)

        vm.setSeatColour(seat, SeatColour.RED)
        assertEquals(SeatColour.RED, state().selectedSeat!!.overrideColour)
        assertEquals(SeatTone.AVOID, state().seatVisuals.getValue(seat).tone)

        vm.setSeatColour(seat, SeatColour.GREEN)
        assertEquals(SeatTone.GREAT, state().selectedSeat!!.visual.tone)

        vm.setSeatColour(seat, null) // "Auto": back to the colour of its rating (3 → OK)
        assertNull(state().selectedSeat!!.overrideColour)
        assertEquals(SeatTone.OK, state().seatVisuals.getValue(seat).tone)
        assertNull(state().message)
    }

    @Test
    fun favouriteSetMoveAndClear() = runTest {
        val (vm, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        val first = SampleLayouts.splitHall.rows[6].seatIds[13]
        val second = SampleLayouts.splitHall.rows[4].seatIds[2]
        assertTrue(state().seatVisuals.values.none { it.isFavourite })

        vm.onSeatClick(first)
        vm.toggleFavourite(first)
        assertTrue(state().selectedSeat!!.isFavourite)
        assertEquals(setOf(first), state().favourites())

        // Marking another seat moves the favourite (one per hall).
        vm.toggleFavourite(second)
        assertEquals(setOf(second), state().favourites())
        assertFalse(state().selectedSeat!!.isFavourite)

        vm.toggleFavourite(second)
        assertTrue(state().favourites().isEmpty())
    }

    @Test
    fun favouriteKeepsTheManualColour() = runTest {
        val (vm, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        val seat = SampleLayouts.splitHall.rows[6].seatIds[13]
        vm.setSeatColour(seat, SeatColour.YELLOW)
        vm.toggleFavourite(seat)
        assertEquals(SeatVisual(SeatTone.OK, isFavourite = true), state().seatVisuals[seat])
        vm.toggleFavourite(seat)
        assertEquals(SeatVisual(SeatTone.OK), state().seatVisuals[seat])
    }

    @Test
    fun failedWritesBecomeMessages() = runTest {
        val (vm, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        val seat = SampleLayouts.splitHall.rows[6].seatIds[13]
        repository.failure = IllegalStateException("disk full")
        vm.setSeatColour(seat, SeatColour.RED)
        assertEquals(HallDetailViewModel.COLOUR_FAILED, state().message)
        vm.messageShown()
        assertNull(state().message)

        vm.toggleFavourite(seat)
        assertEquals(HallDetailViewModel.FAVOURITE_FAILED, state().message)
        vm.messageShown()
        assertEquals(SeatVisual.Neutral, state().seatVisuals[seat]) // nothing changed

        // A seat that is no longer in the layout (e.g. removed in the editor meanwhile).
        repository.failure = null
        vm.setSeatColour(SeatId(9_999), SeatColour.GREEN)
        assertEquals(HallDetailViewModel.SEAT_GONE, state().message)
    }

    private fun HallDetailUiState.Loaded.favourites(): Set<SeatId> =
        seatVisuals.filterValues { it.isFavourite }.keys
}
