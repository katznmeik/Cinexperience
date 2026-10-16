package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.data.repository.SaveVisitResult
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * A save or delete that is still running: a second tap on Save or
 * Delete, Back, Discard and a photo pick must not insert a second visit, delete
 * the photo the visit is about to reference, or close the form twice.
 */
class VisitEditInFlightTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
    private val slow = SlowVisitRepository(repository)
    private val photos = FakeVisitPhotoStore()
    private val today = LocalDate.of(2026, 10, 14)

    /** Collects every form, so a test can count how often it closed. */
    private val collectors = CoroutineScope(mainDispatcherRule.dispatcher)

    @After
    fun stopCollecting() = collectors.cancel()

    private fun viewModel(mode: VisitEditMode) =
        VisitEditViewModel(mode, repository, slow, photos, SavedStateHandle(), today = { today })

    private val VisitEditViewModel.state: VisitEditUiState get() = uiState.value

    /** How often the form has turned to closed, counted as it happens. */
    private fun VisitEditViewModel.closings(): () -> Int {
        var count = 0
        collectors.launch {
            uiState.map { it.isClosed }.distinctUntilChanged().collect { if (it) count++ }
        }
        return { count }
    }

    private fun visits() = repository.store.value.visits

    @Test
    fun aSaveInFlightIgnoresASecondSaveCloseDiscardAndPhotoPicks() {
        val before = visits().size
        val earlierIds = visits().map { it.id }.toSet()
        val vm = viewModel(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1))
        val closings = vm.closings()
        vm.onGalleryPhotoPicked("content://media/picked/1")
        val photo = vm.state.form.photoPath!!
        vm.rateSeat(4)

        vm.save()
        assertTrue(vm.state.isSaving)
        assertFalse(vm.state.canSave)

        vm.save()
        vm.requestClose()
        vm.discard()
        vm.onGalleryPhotoPicked("content://media/picked/2")
        vm.removePhoto()
        assertEquals(1, slow.saveCalls)
        assertNull(vm.state.dialog)
        assertFalse(vm.state.isClosed)
        assertEquals(photo, vm.state.form.photoPath)
        assertEquals(listOf("content://media/picked/1"), photos.importedSources)
        assertTrue(photos.deleted.isEmpty())
        assertEquals(before, visits().size) // still waiting

        slow.saveGate.complete(Unit)

        assertEquals(1, slow.saveCalls)
        assertEquals(before + 1, visits().size)
        val saved = visits().single { it.id !in earlierIds }
        assertEquals(photo, saved.photoPath)
        assertEquals(Rating(4), saved.seatRating)
        assertTrue(photo in photos.files)
        assertTrue(photos.deleted.isEmpty())
        assertTrue(vm.state.isClosed)
        assertFalse(vm.state.isSaving)
        assertEquals(1, closings())

        // Closed: nothing else gets through either.
        vm.save()
        vm.discard()
        assertEquals(1, slow.saveCalls)
        assertTrue(photos.deleted.isEmpty())
        assertEquals(1, closings())
    }

    @Test
    fun aDeleteInFlightIgnoresASecondDeleteSaveCloseDiscardAndPhotoPicks() {
        val visit = Visit(
            id = 900,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 9, 20),
            filmTitle = "Dune",
            photoPath = "photos/visit-old.jpg",
        )
        repository.store.value = repository.store.value.copy(visits = visits() + visit)
        photos.files += "photos/visit-old.jpg"
        val vm = viewModel(VisitEditMode.Edit(visit.id))
        val closings = vm.closings()
        vm.onGalleryPhotoPicked("content://media/picked/1")
        val sessionPhoto = vm.state.form.photoPath!!

        vm.requestDelete()
        vm.confirmDelete()
        assertTrue(vm.state.isSaving)

        vm.confirmDelete()
        vm.requestDelete()
        vm.save()
        vm.requestClose()
        vm.discard()
        vm.onGalleryPhotoPicked("content://media/picked/2")
        assertEquals(1, slow.deleteCalls)
        assertEquals(0, slow.saveCalls)
        assertNull(vm.state.dialog)
        assertFalse(vm.state.isClosed)
        assertEquals(listOf("content://media/picked/1"), photos.importedSources)
        assertTrue(photos.deleted.isEmpty()) // nothing is deleted before the visit is
        assertTrue(visits().any { it.id == visit.id })

        slow.deleteGate.complete(Unit)

        assertEquals(1, slow.deleteCalls)
        assertEquals(0, slow.saveCalls)
        assertFalse(visits().any { it.id == visit.id })
        // Each photo deleted exactly once: the stored one and the one picked in this session.
        assertEquals(listOf("photos/visit-old.jpg", sessionPhoto).sorted(), photos.deleted.sorted())
        assertTrue(photos.files.isEmpty())
        assertTrue(vm.state.isClosed)
        assertFalse(vm.state.isSaving)
        assertEquals(1, closings())
    }

    /** Delegates to the fake but suspends [saveVisit] and [deleteVisit] until their gates complete. */
    private class SlowVisitRepository(private val delegate: FakeCinexRepository) : VisitRepository by delegate {
        val saveGate = CompletableDeferred<Unit>()
        val deleteGate = CompletableDeferred<Unit>()
        var saveCalls = 0
        var deleteCalls = 0

        override suspend fun saveVisit(visit: Visit): SaveVisitResult {
            saveCalls++
            saveGate.await()
            return delegate.saveVisit(visit)
        }

        override suspend fun deleteVisit(visitId: Long): Boolean {
            deleteCalls++
            deleteGate.await()
            return delegate.deleteVisit(visitId)
        }
    }
}
