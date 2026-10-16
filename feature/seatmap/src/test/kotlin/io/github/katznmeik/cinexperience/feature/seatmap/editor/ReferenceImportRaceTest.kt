package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * A reference screenshot that is still being copied when the editor saves or
 * discards (spec §6.5): saving is refused until it has landed (so it is never
 * missing from a save, and the user is never stuck on "Saving"), and a discard
 * deletes it once it lands. Imports are held at a gate, so the order is fixed.
 */
class ReferenceImportRaceTest {

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
                    screenWidth = 4f,
                    screenDistance = 2f,
                    referenceImagePath = OLD_IMAGE,
                ),
            ),
        ),
    )
    private val files = FakeReferenceImageStore(setOf(OLD_IMAGE))
    private val images = GatedReferenceImageStore(files)
    // Created in the test, once the rule has set the main dispatcher.
    private val vm by lazy { LayoutEditorViewModel(HALL, repository, images) }

    private val state get() = vm.uiState.value
    private fun storedHall() = repository.store.value.halls.single { it.id == HALL }

    /** The path of the [n]th import to finish (the fake numbers them as they land). */
    private fun landed(n: Int) = "${ReferenceImageStore.FOLDER}/hall-$HALL-$n.jpg"

    @Test
    fun saveIsRefusedWhileAScreenshotIsBeingImportedAndSavesItOnceItLanded() {
        vm.onReferenceImagePicked("content://picker/1")
        assertTrue(state.isImportingReference)

        // Refused, not waiting: nothing is "Saving", so Discard and Back stay available.
        vm.save()
        assertFalse(state.isSaving)
        assertFalse(state.isClosed)
        assertEquals(OLD_IMAGE, storedHall().referenceImagePath)

        images.open(0)
        assertFalse(state.isImportingReference)
        vm.save()
        assertTrue(state.isClosed)
        assertEquals(landed(1), storedHall().referenceImagePath)
        assertEquals(setOf(landed(1)), files.files) // the replaced screenshot is gone, nothing left over
        assertEquals(listOf(OLD_IMAGE), files.deleted)
    }

    @Test
    fun saveStaysRefusedUntilEveryRunningImportHasLanded() {
        // The screen starts one import at a time; the ViewModel copes with two anyway.
        vm.onReferenceImagePicked("content://picker/1")
        vm.onReferenceImagePicked("content://picker/2")

        images.open(1) // the second pick lands first
        vm.save()
        assertTrue(state.isImportingReference)
        assertFalse(state.isSaving)

        images.open(0) // the last one to land is the draft's
        vm.save()
        assertTrue(state.isClosed)
        assertEquals(landed(2), storedHall().referenceImagePath)
        assertEquals(setOf(landed(2)), files.files)
        assertEquals(setOf(landed(1), OLD_IMAGE), files.deleted.toSet())
    }

    @Test
    fun theSaveConfirmationIsRefusedWhileAScreenshotIsBeingImported() {
        // Removing seat 8, which has a visit, asks before saving.
        repository.store.value = repository.store.value.copy(
            visits = listOf(Visit(id = 1, hallId = HALL, date = LocalDate.of(2026, 10, 1), seatId = SeatId(8))),
        )
        vm.selectTool(CellType.GAP)
        vm.tapCell(1, 3)
        vm.save()
        assertTrue(state.dialog is EditorDialog.ConfirmSave)

        // (The dialog covers the screen; the ViewModel refuses the confirmation anyway.)
        vm.onReferenceImagePicked("content://picker/1")
        vm.confirmSave()
        assertTrue(state.dialog is EditorDialog.ConfirmSave)
        assertFalse(state.isSaving)

        images.open(0)
        vm.confirmSave()
        assertTrue(state.isClosed)
        assertEquals(landed(1), storedHall().referenceImagePath)
        assertFalse(SeatId(8) in storedHall().layout!!.seatIds)
    }

    @Test
    fun anImportLandingAfterADiscardDeletesItsFile() {
        vm.onReferenceImagePicked("content://picker/1")
        vm.requestDiscard() // nothing changed yet: closes without asking
        assertTrue(state.isClosed)

        images.open(0)
        assertEquals(listOf(landed(1)), files.deleted)
        assertEquals(setOf(OLD_IMAGE), files.files)
        assertEquals(OLD_IMAGE, storedHall().referenceImagePath)
        assertEquals(OLD_IMAGE, state.draft!!.referenceImagePath)
    }

    @Test
    fun anImportStillRunningWhenAChangedDraftIsDiscardedIsDeletedToo() {
        // The first pick landed; the discard deletes it while the second is still being copied.
        vm.onReferenceImagePicked("content://picker/1")
        images.open(0)
        vm.onReferenceImagePicked("content://picker/2")
        vm.requestDiscard()
        vm.discard() // the draft changed: Discard confirms the dialog
        assertTrue(state.isClosed)

        images.open(1)
        assertEquals(setOf(landed(1), landed(2)), files.deleted.toSet())
        assertEquals(setOf(OLD_IMAGE), files.files)
        assertEquals(OLD_IMAGE, storedHall().referenceImagePath)
    }

    /** Delegates to [delegate], but each import waits for its own gate ([open]). */
    private class GatedReferenceImageStore(private val delegate: FakeReferenceImageStore) : ReferenceImageStore by delegate {
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        /** Lets the [index]th import (in the order they started) finish. */
        fun open(index: Int) {
            gates[index].complete(Unit)
        }

        override suspend fun importImage(hallId: Long, sourceUri: String): String {
            val gate = CompletableDeferred<Unit>().also { gates += it }
            gate.await()
            return delegate.importImage(hallId, sourceUri)
        }
    }

    private companion object {
        const val HALL = 7L
        const val OLD_IMAGE = "reference/hall-7-old.jpg"
    }
}
