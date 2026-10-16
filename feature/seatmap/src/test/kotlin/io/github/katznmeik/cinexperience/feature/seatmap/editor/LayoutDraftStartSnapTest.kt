package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * The editor starts with a stored screen width and distance within its range
 * snapped to its steps (N25), e.g. values from a hand-edited backup, which only
 * has to hold finite values within the data limits: so the first step moves by
 * half a seat or row instead of jumping, and a screen as wide as the grid keeps
 * following it. Values outside the editor's range are kept, as the stored offset
 * beyond the grid is (LayoutEditorViewModelTest, LayoutEditorLegacyHallTest).
 */
class LayoutDraftStartSnapTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 6 columns: the editor's screen 1 to 12 seats wide, its centre at most 3 seats off. */
    private val layout = SeatLayout.fromPatterns("SSSSSS", "SSSSSS", firstSeatId = 1)
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = layout, screenWidth = 6f, screenDistance = 2f)

    @Test
    fun theStoredScreenIsSnappedOnLoad() {
        val odd = LayoutDraft.start(hall.copy(screenWidth = 4.3f, screenDistance = 2.2f))
        assertEquals(4.5f, odd.screenWidth)
        assertEquals(2f, odd.screenDistance)
        assertEquals(12f, LayoutDraft.start(hall.copy(screenWidth = 11.9f)).screenWidth)
        assertEquals(15f, LayoutDraft.start(hall.copy(screenDistance = 14.8f)).screenDistance)

        // A distance below the editor's minimum snaps, but is kept below it (see ScreenRules.MIN_DISTANCE).
        assertEquals(0f, LayoutDraft.start(hall.copy(screenDistance = 0.2f)).screenDistance)
        assertEquals(0.5f, LayoutDraft.start(hall.copy(screenDistance = 0.3f)).screenDistance)

        // Values the app writes are unchanged.
        val stored = LayoutDraft.start(hall.copy(screenWidth = 5.5f, screenDistance = 1.5f, screenOffset = -0.5f))
        assertEquals(5.5f, stored.screenWidth)
        assertEquals(1.5f, stored.screenDistance)
        assertEquals(-0.5f, stored.screenOffset)
    }

    @Test
    fun valuesOutsideTheEditorsRangeAreKept() {
        // Wider than this grid allows (within the data limits), narrower than the editor's narrowest.
        assertEquals(50.3f, LayoutDraft.start(hall.copy(screenWidth = 50.3f)).screenWidth)
        assertEquals(0.3f, LayoutDraft.start(hall.copy(screenWidth = 0.3f)).screenWidth)
        // Farther than the data limits allow (an older build's value).
        assertEquals(40f, LayoutDraft.start(hall.copy(screenDistance = 40f)).screenDistance)
        // As before: an offset beyond the grid is kept, only made valid.
        assertEquals(-10f, LayoutDraft.start(hall.copy(screenOffset = -10f)).screenOffset)
        assertEquals(1.5f, LayoutDraft.start(hall.copy(screenOffset = 1.3f)).screenOffset)
    }

    @Test
    fun theFirstStepDoesNotJump() {
        val draft = LayoutDraft.start(hall.copy(screenWidth = 4.3f, screenDistance = 2.2f, screenOffset = 1.3f))
        // Each stepper tap moves the shown value by exactly one step.
        assertEquals(ScreenRules.STEP, draft.withScreenWidth(draft.screenWidth + ScreenRules.STEP).screenWidth - draft.screenWidth)
        assertEquals(ScreenRules.STEP, draft.screenWidth - draft.withScreenWidth(draft.screenWidth - ScreenRules.STEP).screenWidth)
        assertEquals(ScreenRules.STEP, draft.screenDistance - draft.withScreenDistance(draft.screenDistance - ScreenRules.STEP).screenDistance)
        assertEquals(ScreenOffsetRules.STEP, draft.withScreenOffset(draft.screenOffset + ScreenOffsetRules.STEP).screenOffset - draft.screenOffset)
    }

    @Test
    fun aScreenAlmostAsWideAsTheGridFollowsIt() {
        val draft = LayoutDraft.start(hall.copy(screenWidth = 5.9f))
        assertEquals(6f, draft.screenWidth)
        assertEquals(8f, draft.withColumnCount(8).screenWidth)
    }

    @Test
    fun loadingASnappedScreenIsNoUnsavedChange() {
        val odd = hall.copy(screenWidth = 4.3f, screenDistance = 2.2f)
        val store = FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(odd))
        val vm = LayoutEditorViewModel(1, FakeCinexRepository(store), FakeReferenceImageStore())
        val state = vm.uiState.value
        assertEquals(4.5f, state.draft!!.screenWidth)
        assertEquals(2f, state.draft!!.screenDistance)
        assertFalse(state.hasUnsavedChanges)
        assertFalse(state.canUndo)
    }
}
