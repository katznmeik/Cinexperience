package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.SeatLayout
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

/**
 * A reference screenshot that is still being copied when the editor saves or
 * discards (spec §6.5): a save waits for it and saves it, a discard deletes it
 * once it lands. Imports are held at a gate, so the order is fixed.
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
    fun aSaveWaitsForTheScreenshotStillBeingImportedAndSavesIt() {
        vm.onReferenceImagePicked("content://picker/1")
        assertTrue(state.isImportingReference)

        vm.save()
        assertTrue(state.isSaving)
        assertFalse(state.isClosed)

        images.open(0)
        assertTrue(state.isClosed)
        assertEquals(landed(1), storedHall().referenceImagePath)
        assertEquals(setOf(landed(1)), files.files) // the replaced screenshot is gone, nothing left over
        assertEquals(listOf(OLD_IMAGE), files.deleted)
    }

    @Test
    fun aSaveWaitsForEveryRunningImport() {
        // "Replace" can start a second import while the first one runs.
        vm.onReferenceImagePicked("content://picker/1")
        vm.onReferenceImagePicked("content://picker/2")
        vm.save()

        images.open(1) // the second pick lands first
        assertTrue(state.isSaving)
        assertTrue(state.isImportingReference)
        assertFalse(state.isClosed)

        images.open(0) // the last one to land is the draft's, like without a save
        assertTrue(state.isClosed)
        assertEquals(landed(2), storedHall().referenceImagePath)
        assertEquals(setOf(landed(2)), files.files)
        assertEquals(setOf(landed(1), OLD_IMAGE), files.deleted.toSet())
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
