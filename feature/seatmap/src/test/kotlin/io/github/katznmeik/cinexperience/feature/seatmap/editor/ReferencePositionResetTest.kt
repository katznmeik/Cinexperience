package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** "Reset position" in the reference panel: the screenshot's placement from the gestures is dropped. */
class ReferencePositionResetTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(
        FakeStore(
            cinemas = listOf(Cinema(id = 1, name = "Kino")),
            halls = listOf(
                Hall(
                    id = HALL,
                    cinemaId = 1,
                    name = "Saal 1",
                    layout = SeatLayout.fromPatterns("SSSS", "SSSS", firstSeatId = 1),
                    referenceImagePath = IMAGE,
                ),
            ),
        ),
    )

    // Created in the test, once the rule has set the main dispatcher.
    private val vm by lazy { LayoutEditorViewModel(HALL, repository, FakeReferenceImageStore(setOf(IMAGE))) }

    @Test
    fun resetDropsThePlacementAndKeepsTheScreenshot() {
        val placed = ReferenceAlignment(left = 0.1f, top = 0.2f, width = 0.5f)
        vm.setReferenceAlignment(placed)
        assertEquals(placed, vm.uiState.value.referenceAlignment)

        vm.resetReferencePosition()

        assertNull(vm.uiState.value.referenceAlignment)
        assertEquals(IMAGE, vm.uiState.value.draft?.referenceImagePath)
    }

    private companion object {
        const val HALL = 7L
        const val IMAGE = "reference/hall-7-a.jpg"
    }
}
