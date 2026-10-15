package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * A visit form restored after process death checks its unsaved photos: the
 * system may have removed them while the app was gone (spec §6.4 photo).
 */
class VisitEditRestoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
    private val photos = FakeVisitPhotoStore()
    private val today = LocalDate.of(2026, 10, 14)
    private val handle = SavedStateHandle()

    private fun viewModel(mode: VisitEditMode = VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1)) =
        VisitEditViewModel(mode, repository, repository, photos, handle) { today }

    private val VisitEditViewModel.state: VisitEditUiState get() = uiState.value

    /** A form with the film title set and the gallery photos [uris] picked one after the other. */
    private fun formWithPhotos(vararg uris: String): VisitEditViewModel = viewModel().apply {
        setFilmTitle("Anora")
        uris.forEach { onGalleryPhotoPicked(it) }
    }

    /** The file is gone, as if the startup sweep or the user had removed it while the app was dead. */
    private fun lose(path: String) {
        assertTrue(photos.files.remove(path))
    }

    @Test
    fun aRestoredPhotoWhoseFileIsGoneIsClearedWithAMessage() {
        formWithPhotos("content://media/picked/1")
        lose("photos/visit-1.jpg")

        val restored = viewModel()
        with(restored.state) {
            assertEquals(VisitEditStatus.Ready, status)
            assertEquals("Anora", form.filmTitle)
            assertNull(form.photoPath)
            assertEquals(VisitEditViewModel.PHOTO_GONE, message)
        }

        // The draft no longer holds the photo: a second restore has nothing to report.
        restored.messageShown()
        val again = viewModel()
        assertNull(again.state.form.photoPath)
        assertNull(again.state.message)
        assertEquals("Anora", again.state.form.filmTitle)
    }

    @Test
    fun aRestoredPhotoThatStillExistsIsKept() {
        formWithPhotos("content://media/picked/1")

        val restored = viewModel()
        assertEquals("photos/visit-1.jpg", restored.state.form.photoPath)
        assertNull(restored.state.message)

        restored.save()
        assertEquals("photos/visit-1.jpg", repository.store.value.visits.single { it.filmTitle == "Anora" }.photoPath)
        assertTrue(photos.deleted.isEmpty())
    }

    @Test
    fun aMissingEarlierSessionPhotoIsNotDeletedOnDiscard() {
        formWithPhotos("content://media/picked/1", "content://media/picked/2")
        lose("photos/visit-1.jpg")

        val restored = viewModel()
        assertEquals("photos/visit-2.jpg", restored.state.form.photoPath)
        assertNull(restored.state.message) // the form's own photo is still there

        restored.discard()
        assertEquals(listOf("photos/visit-2.jpg"), photos.deleted)
    }

    @Test
    fun aMissingEarlierSessionPhotoIsNotDeletedOnSave() {
        formWithPhotos("content://media/picked/1", "content://media/picked/2")
        lose("photos/visit-1.jpg")

        val restored = viewModel()
        restored.save()
        assertTrue(restored.state.isClosed)
        assertTrue(photos.deleted.isEmpty())
        assertEquals("photos/visit-2.jpg", repository.store.value.visits.single { it.filmTitle == "Anora" }.photoPath)
    }

    @Test
    fun aSavedVisitsOwnPhotoIsNeverTouched() {
        val visit = Visit(
            id = 900,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 9, 20),
            filmTitle = "Dune",
            screeningFormat = ScreeningFormat.OV,
            photoPath = "photos/visit-old.jpg",
        )
        repository.store.value = repository.store.value.copy(visits = repository.store.value.visits + visit)
        // Not in the store's files: even a missing saved photo stays the visit's, as in the database.
        viewModel(VisitEditMode.Edit(visit.id)).setNote("Changed")

        val restored = viewModel(VisitEditMode.Edit(visit.id))
        assertEquals("photos/visit-old.jpg", restored.state.form.photoPath)
        assertNull(restored.state.message)
        restored.save()
        assertEquals("photos/visit-old.jpg", repository.store.value.visits.single { it.id == visit.id }.photoPath)
        assertTrue(photos.deleted.isEmpty())
    }
}
