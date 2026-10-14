package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class VisitEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
    private val photos = FakeVisitPhotoStore()
    private val today = LocalDate.of(2026, 10, 14)

    /** Row 7, seat 6 of the split hall (Delphi LUX Saal 1). */
    private val seatG6 = SampleLayouts.splitHall.rows[6].seatIds[9]

    private fun viewModel(mode: VisitEditMode) = VisitEditViewModel(mode, repository, repository, photos, today = { today })

    private fun add(hallId: Long? = SampleData.DELPHI_LUX_SAAL_1) = viewModel(VisitEditMode.Add(hallId))

    /** A stored visit to Saal 1 in seat G 6 with a photo; returns its id. */
    private fun storedVisit(photoPath: String? = "photos/visit-old.jpg", seat: SeatId? = seatG6): Long {
        val visit = Visit(
            id = 900,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 9, 20),
            filmTitle = "Dune",
            seatId = seat,
            screeningFormat = ScreeningFormat.OV,
            seatRating = Rating(4),
            note = "Great sound",
            photoPath = photoPath,
        )
        repository.store.value = repository.store.value.copy(visits = repository.store.value.visits + visit)
        photoPath?.let { photos.files += it }
        return visit.id
    }

    private fun stored(id: Long): Visit? = repository.store.value.visits.firstOrNull { it.id == id }

    private val VisitEditViewModel.state: VisitEditUiState get() = uiState.value

    private fun VisitEditViewModel.pickSeat(seatId: SeatId) {
        openSeatPicker()
        tapPickerSeat(seatId)
        confirmSeat()
    }

    @Test
    fun addFromAHallPrefillsCinemaHallAndToday() {
        val vm = add()
        with(vm.state) {
            assertEquals(VisitEditStatus.Ready, status)
            assertFalse(isEdit)
            assertEquals("delphi LUX", cinemaName)
            assertEquals("Saal 1", hallName)
            assertEquals(7, halls.size)
            assertEquals(today, form.date)
            assertEquals(SampleLayouts.splitHall, layout)
            assertNull(seatPickerUnavailable)
            assertFalse(hasUnsavedChanges)
            assertTrue(canSave)
        }
    }

    @Test
    fun withoutAHallTheUserPicksCinemaThenHall() {
        val vm = add(hallId = null)
        assertFalse(vm.state.canSave) // a hall is required
        assertEquals(VisitEditViewModel.NO_HALL_FOR_SEAT, vm.state.seatPickerUnavailable)
        assertTrue(vm.state.halls.isEmpty())

        vm.selectCinema(SampleData.DELPHI_LUX)
        with(vm.state) {
            assertEquals("delphi LUX", cinemaName)
            assertNull(hallName)
            assertEquals((1..6).map { "Saal $it" } + "Saal 7", halls.map { it.name })
            assertFalse(canSave)
        }

        vm.selectHall(502)
        assertEquals("Saal 2", vm.state.hallName)
        assertTrue(vm.state.canSave)

        // A cinema with a single hall selects it.
        vm.selectCinema(2)
        assertEquals("Capitol Dahlem", vm.state.cinemaName)
        assertEquals(201L, vm.state.form.hallId)

        // Halls of other cinemas or unknown halls are ignored.
        vm.selectHall(999)
        assertEquals(201L, vm.state.form.hallId)
    }

    @Test
    fun changingTheHallOrCinemaClearsTheSeat() {
        val vm = add()
        vm.pickSeat(seatG6)
        assertEquals(SeatLabel("7", 6), vm.state.seatLabel)

        vm.selectCinema(SampleData.DELPHI_LUX) // the hall's own cinema: nothing changes
        assertEquals(seatG6, vm.state.form.seatId)

        vm.selectHall(502)
        assertNull(vm.state.form.seatId)
        assertNull(vm.state.seatLabel)
        assertEquals(VisitEditViewModel.SEAT_CLEARED, vm.state.message)
        vm.messageShown()

        vm.selectHall(SampleData.DELPHI_LUX_SAAL_1)
        vm.pickSeat(seatG6)
        vm.selectCinema(SampleData.BABYLON)
        assertNull(vm.state.form.hallId)
        assertNull(vm.state.form.seatId)
        assertEquals(VisitEditViewModel.SEAT_CLEARED, vm.state.message)
    }

    @Test
    fun seatPickerSelectsTogglesAndConfirms() {
        val vm = add()
        vm.openSeatPicker()
        assertEquals(SeatPickerState(null), vm.state.seatPicker)
        vm.tapPickerSeat(seatG6)
        assertEquals(seatG6, vm.state.seatPicker?.seatId)
        vm.tapPickerSeat(seatG6) // tapping again deselects
        assertNull(vm.state.seatPicker?.seatId)
        vm.choosePickerSeat(seatG6)
        vm.choosePickerSeat(seatG6) // the row/number picker never toggles
        assertEquals(seatG6, vm.state.seatPicker?.seatId)
        assertNull(vm.state.form.seatId) // not taken over until confirmed

        vm.confirmSeat()
        assertNull(vm.state.seatPicker)
        assertEquals(seatG6, vm.state.form.seatId)
        assertTrue(vm.state.hasUnsavedChanges)

        // Closing the picker keeps the form's seat; Back closes the picker first.
        vm.openSeatPicker()
        vm.tapPickerSeat(SampleLayouts.splitHall.rows[0].seatIds[0])
        vm.requestClose()
        assertNull(vm.state.seatPicker)
        assertFalse(vm.state.isClosed)
        assertEquals(seatG6, vm.state.form.seatId)

        vm.clearSeat()
        assertNull(vm.state.form.seatId)
    }

    @Test
    fun theSeatCantBePickedWithoutALayout() {
        val vm = add(SampleData.DELPHI_LUX_SAAL_6)
        assertEquals(VisitEditViewModel.NO_LAYOUT, vm.state.seatPickerUnavailable)
        vm.openSeatPicker()
        assertNull(vm.state.seatPicker)
        assertTrue(vm.state.canSave) // saving needs only the hall
    }

    @Test
    fun tappingTheSelectedStarClearsTheRating() {
        val vm = add()
        vm.rateSeat(4)
        assertEquals(Rating(4), vm.state.form.seatRating)
        vm.rateSeat(2)
        assertEquals(Rating(2), vm.state.form.seatRating)
        vm.rateSeat(2)
        assertNull(vm.state.form.seatRating)

        vm.rateHall(HallCategory.SOUND, 5)
        vm.rateHall(HallCategory.LEGROOM, 3)
        assertEquals(Rating(5), vm.state.form.hallRatings.sound)
        vm.rateHall(HallCategory.SOUND, 5)
        assertNull(vm.state.form.hallRatings.sound)
        assertEquals(Rating(3), vm.state.form.hallRatings.legroom)

        vm.rateCinema(CinemaCategory.POPCORN, 1)
        assertEquals(Rating(1), vm.state.form.cinemaRatings.popcorn)
        vm.rateCinema(CinemaCategory.POPCORN, 1)
        assertNull(vm.state.form.cinemaRatings.popcorn)
    }

    @Test
    fun tappingTheSelectedFormatClearsIt() {
        val vm = add()
        vm.toggleFormat(ScreeningFormat.OMU)
        assertEquals(ScreeningFormat.OMU, vm.state.form.format)
        vm.toggleFormat(ScreeningFormat.SEVENTY_MM)
        assertEquals(ScreeningFormat.SEVENTY_MM, vm.state.form.format)
        vm.toggleFormat(ScreeningFormat.SEVENTY_MM)
        assertNull(vm.state.form.format)
    }

    @Test
    fun savingANewVisitInsertsItAndCloses() {
        val vm = add()
        vm.setDate(LocalDate.of(2026, 10, 10))
        vm.setFilmTitle("  Perfect Days ")
        vm.toggleFormat(ScreeningFormat.OMU)
        vm.pickSeat(seatG6)
        vm.rateSeat(5)
        vm.rateHall(HallCategory.PICTURE, 4)
        vm.rateCinema(CinemaCategory.ATMOSPHERE, 5)
        vm.setNote("  ")
        val before = repository.store.value.visits.size

        vm.save()

        assertTrue(vm.state.isClosed)
        val saved = repository.store.value.visits.last()
        assertEquals(before + 1, repository.store.value.visits.size)
        assertEquals(SampleData.DELPHI_LUX_SAAL_1, saved.hallId)
        assertEquals(LocalDate.of(2026, 10, 10), saved.date)
        assertEquals("Perfect Days", saved.filmTitle)
        assertEquals(ScreeningFormat.OMU, saved.screeningFormat)
        assertEquals(seatG6, saved.seatId)
        assertEquals(Rating(5), saved.seatRating)
        assertEquals(Rating(4), saved.hallRatings.picture)
        assertEquals(Rating(5), saved.cinemaRatings.atmosphere)
        assertNull(saved.note)
        assertNull(saved.photoPath)
    }

    @Test
    fun editingLoadsAndUpdatesTheVisit() {
        val id = storedVisit()
        val vm = viewModel(VisitEditMode.Edit(id))
        with(vm.state) {
            assertTrue(isEdit)
            assertEquals("Dune", form.filmTitle)
            assertEquals("Great sound", form.note)
            assertEquals(SeatLabel("7", 6), seatLabel)
            assertEquals("Saal 1", hallName)
            assertFalse(hasUnsavedChanges)
        }
        vm.setNote("Great sound, sticky floor")
        vm.save()

        assertTrue(vm.state.isClosed)
        val saved = stored(id)!!
        assertEquals("Great sound, sticky floor", saved.note)
        assertEquals(seatG6, saved.seatId)
        assertEquals("photos/visit-old.jpg", saved.photoPath)
        assertTrue(photos.deleted.isEmpty())
        assertEquals(1, repository.store.value.visits.count { it.id == id })
    }

    @Test
    fun aSeatRemovedFromTheLayoutIsKeptUntilChanged() {
        val id = storedVisit(seat = SeatId(9_999))
        val vm = viewModel(VisitEditMode.Edit(id))
        assertTrue(vm.state.seatMissing)
        assertNull(vm.state.seatLabel)
        vm.rateSeat(3)
        vm.save()
        assertEquals(SeatId(9_999), stored(id)!!.seatId)
    }

    @Test
    fun unsavedChangesAskBeforeClosing() {
        val vm = add()
        vm.requestClose() // nothing entered: closes right away
        assertTrue(vm.state.isClosed)

        val other = add()
        other.setFilmTitle("   ") // only whitespace is no change
        assertFalse(other.state.hasUnsavedChanges)
        other.setFilmTitle("Anora")
        assertTrue(other.state.hasUnsavedChanges)
        other.requestClose()
        assertEquals(VisitEditDialog.ConfirmDiscard, other.state.dialog)
        assertFalse(other.state.isClosed)
        other.dismissDialog()
        assertNull(other.state.dialog)
        other.setFilmTitle("")
        assertFalse(other.state.hasUnsavedChanges)
        other.rateSeat(3)
        other.rateSeat(3) // back to unrated
        assertFalse(other.state.hasUnsavedChanges)

        other.toggleFormat(ScreeningFormat.THREE_D)
        other.requestClose()
        other.discard()
        assertTrue(other.state.isClosed)
        assertEquals(SampleData.visits.size, repository.store.value.visits.size) // nothing saved
    }

    @Test
    fun deleteAsksAndDeletesTheVisitWithItsPhoto() {
        val id = storedVisit()
        val vm = viewModel(VisitEditMode.Edit(id))
        vm.onGalleryPhotoPicked("content://media/picked/1")
        vm.requestDelete()
        assertEquals(VisitEditDialog.ConfirmDelete, vm.state.dialog)
        vm.confirmDelete()

        assertTrue(vm.state.isClosed)
        assertNull(stored(id))
        assertEquals(setOf("photos/visit-old.jpg", "photos/visit-1.jpg"), photos.deleted.toSet())
        assertTrue(photos.files.isEmpty())
    }

    @Test
    fun aNewVisitCantBeDeleted() {
        val vm = add()
        vm.requestDelete()
        assertNull(vm.state.dialog)
    }

    @Test
    fun replacedPhotosAreDeletedOnlyAfterSaving() {
        val id = storedVisit()
        val vm = viewModel(VisitEditMode.Edit(id))
        vm.onGalleryPhotoPicked("content://media/picked/1")
        assertEquals("photos/visit-1.jpg", vm.state.form.photoPath)
        vm.onGalleryPhotoPicked("content://media/picked/2")
        assertEquals("photos/visit-2.jpg", vm.state.form.photoPath)
        assertTrue(photos.deleted.isEmpty()) // nothing is deleted before the save
        vm.onGalleryPhotoPicked(null) // cancelled picker: no change
        assertEquals("photos/visit-2.jpg", vm.state.form.photoPath)

        vm.save()

        assertEquals("photos/visit-2.jpg", stored(id)!!.photoPath)
        assertEquals(setOf("photos/visit-1.jpg", "photos/visit-old.jpg"), photos.deleted.toSet())
        assertEquals(setOf("photos/visit-2.jpg"), photos.files)
    }

    @Test
    fun removingThePhotoDeletesItsFileOnSave() {
        val id = storedVisit()
        val vm = viewModel(VisitEditMode.Edit(id))
        vm.removePhoto()
        assertNull(vm.state.form.photoPath)
        assertTrue(vm.state.hasUnsavedChanges)
        assertTrue(photos.deleted.isEmpty())
        vm.save()
        assertNull(stored(id)!!.photoPath)
        assertEquals(listOf("photos/visit-old.jpg"), photos.deleted)
    }

    @Test
    fun discardingDeletesOnlyThisSessionsPhotos() {
        val id = storedVisit()
        val vm = viewModel(VisitEditMode.Edit(id))
        vm.onGalleryPhotoPicked("content://media/picked/1")
        vm.requestClose()
        assertEquals(VisitEditDialog.ConfirmDiscard, vm.state.dialog)
        vm.discard()
        assertTrue(vm.state.isClosed)
        assertEquals(listOf("photos/visit-1.jpg"), photos.deleted)
        assertEquals(setOf("photos/visit-old.jpg"), photos.files)
        assertEquals("photos/visit-old.jpg", stored(id)!!.photoPath)
    }

    @Test
    fun cameraCapturesAreImportedAndTheirTempFileDeleted() {
        val vm = add()
        vm.requestCamera()
        val uri = requireValue(vm.state.cameraRequest)
        assertTrue(vm.state.isPhotoBusy)
        assertFalse(vm.state.canSave) // wait for the photo
        vm.onCameraLaunched()
        assertNull(vm.state.cameraRequest)

        vm.onCameraResult(success = true)
        assertEquals(listOf(uri), photos.importedSources)
        assertTrue(photos.captures.isEmpty())
        assertEquals("photos/visit-2.jpg", vm.state.form.photoPath)
        assertFalse(vm.state.isPhotoBusy)

        // Cancelled in the camera app: nothing imported, the capture file is deleted.
        vm.requestCamera()
        vm.onCameraLaunched()
        vm.onCameraResult(success = false)
        assertEquals(1, photos.importedSources.size)
        assertTrue(photos.captures.isEmpty())
        assertEquals("photos/visit-2.jpg", vm.state.form.photoPath)

        // No camera app.
        vm.requestCamera()
        vm.onCameraUnavailable()
        assertEquals(VisitEditViewModel.NO_CAMERA, vm.state.message)
        assertTrue(photos.captures.isEmpty())
        assertFalse(vm.state.isPhotoBusy)
    }

    private fun <T> requireValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    @Test
    fun photoErrorsBecomeMessages() {
        val vm = add()
        photos.importFailure = IOException("broken")
        vm.onGalleryPhotoPicked("content://media/picked/1")
        assertEquals(VisitEditViewModel.PHOTO_FAILED, vm.state.message)
        assertNull(vm.state.form.photoPath)
        assertFalse(vm.state.isPhotoBusy)

        vm.messageShown()
        photos.captureFailure = IOException("no space")
        vm.requestCamera()
        assertEquals(VisitEditViewModel.CAMERA_FAILED, vm.state.message)
        assertNull(vm.state.cameraRequest)
        assertFalse(vm.state.isPhotoBusy)
    }

    @Test
    fun saveErrorsBecomeMessagesAndKeepTheForm() {
        val vm = add()
        vm.setFilmTitle("Anora")
        vm.onGalleryPhotoPicked("content://media/picked/1")
        repository.failure = IOException("disk full")
        vm.save()
        with(vm.state) {
            assertEquals(VisitEditViewModel.SAVE_FAILED, message)
            assertFalse(isSaving)
            assertFalse(isClosed)
            assertEquals("Anora", form.filmTitle)
        }
        assertTrue(photos.deleted.isEmpty()) // the photo is still needed

        repository.failure = null
        vm.save()
        assertTrue(vm.state.isClosed)
        assertEquals("photos/visit-1.jpg", repository.store.value.visits.last().photoPath)
    }

    @Test
    fun deleteErrorsBecomeMessages() {
        val id = storedVisit()
        val vm = viewModel(VisitEditMode.Edit(id))
        repository.failure = IOException("disk full")
        vm.requestDelete()
        vm.confirmDelete()
        assertEquals(VisitEditViewModel.DELETE_FAILED, vm.state.message)
        assertFalse(vm.state.isClosed)
        assertTrue(photos.deleted.isEmpty())
    }

    @Test
    fun aHallDeletedMeanwhileFailsTheSaveWithAMessage() {
        val hallId = runBlocking { repository.createHall(SampleData.DELPHI_LUX, "Saal 8") }
        val vm = add(hallId)
        repository.store.value = repository.store.value.copy(halls = repository.store.value.halls.filter { it.id != hallId })
        vm.save() // the form no longer offers the hall: nothing to save
        assertFalse(vm.state.isClosed)
        assertNull(vm.state.form.hallId)
    }

    @Test
    fun aSeatThatLeftTheLayoutIsClearedOnSaveWithAMessage() {
        val vm = add()
        vm.pickSeat(seatG6)
        // The layout is redrawn elsewhere meanwhile: the seat is gone.
        val redrawn = SeatLayout.fromPatterns("SSSS", firstSeatId = 500)
        repository.store.value = repository.store.value.copy(
            halls = repository.store.value.halls.map { if (it.id == SampleData.DELPHI_LUX_SAAL_1) it.copy(layout = redrawn) else it },
        )
        assertTrue(vm.state.seatMissing)

        vm.save()

        with(vm.state) {
            assertFalse(isClosed)
            assertTrue(isEdit)
            assertEquals(VisitEditViewModel.SEAT_CLEARED_ON_SAVE, message)
            assertNull(form.seatId)
            assertFalse(hasUnsavedChanges)
        }
        val saved = repository.store.value.visits.last()
        assertNull(saved.seatId)

        // Saving again updates the same visit.
        vm.setFilmTitle("Second try")
        vm.save()
        assertTrue(vm.state.isClosed)
        assertEquals("Second try", stored(saved.id)!!.filmTitle)
        assertEquals(SampleData.visits.size + 1, repository.store.value.visits.size)
    }

    /** Regression: the photo of a visit saved without its seat must survive a later discard. */
    @Test
    fun aPhotoSavedWhileTheFormStaysOpenIsNotDeletedOnDiscard() {
        val vm = add()
        vm.pickSeat(seatG6)
        vm.onGalleryPhotoPicked("content://media/picked/1")
        repository.store.value = repository.store.value.copy(
            halls = repository.store.value.halls.map {
                if (it.id == SampleData.DELPHI_LUX_SAAL_1) it.copy(layout = SeatLayout.fromPatterns("SSSS", firstSeatId = 500)) else it
            },
        )
        vm.save()
        assertFalse(vm.state.isClosed) // seat cleared: the form stays open
        val saved = repository.store.value.visits.last()
        assertEquals("photos/visit-1.jpg", saved.photoPath)

        vm.toggleFormat(ScreeningFormat.OV)
        vm.requestClose()
        vm.discard()

        assertTrue(photos.deleted.isEmpty())
        assertEquals(setOf("photos/visit-1.jpg"), photos.files)
        assertEquals("photos/visit-1.jpg", stored(saved.id)!!.photoPath)
    }

    @Test
    fun theEditSurvivesProcessDeathDuringTheCameraHandOff() {
        val handle = SavedStateHandle()
        val first = VisitEditViewModel(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1), repository, repository, photos, handle) { today }
        first.setFilmTitle("Anora")
        first.pickSeat(seatG6)
        first.rateHall(HallCategory.SOUND, 4)
        first.toggleFormat(ScreeningFormat.OMU)
        first.onGalleryPhotoPicked("content://media/picked/1")
        first.requestCamera()
        val capture = requireValue(first.state.cameraRequest)
        first.onCameraLaunched()

        // The process dies while the camera app is open; a new ViewModel gets the saved state.
        val restored = VisitEditViewModel(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1), repository, repository, photos, handle) { today }
        with(restored.state) {
            assertEquals(VisitEditStatus.Ready, status)
            assertEquals("Anora", form.filmTitle)
            assertEquals(seatG6, form.seatId)
            assertEquals(Rating(4), form.hallRatings.sound)
            assertEquals(ScreeningFormat.OMU, form.format)
            assertTrue(hasUnsavedChanges)
        }
        restored.onCameraResult(success = true)
        assertEquals(listOf("content://media/picked/1", capture), photos.importedSources)
        assertFalse(capture in photos.captures)
        val cameraPhoto = requireValue(restored.state.form.photoPath)

        // Both photos of the lost session are still known: discarding deletes them.
        restored.requestClose()
        restored.discard()
        assertEquals(setOf("photos/visit-1.jpg", cameraPhoto), photos.deleted.toSet())
        assertNull(handle.get<String>(VisitEditViewModel.DRAFT_KEY)) // nothing to restore any more
    }

    @Test
    fun aRestoredEditKeepsItsBaselineAndVisit() {
        val id = storedVisit()
        val handle = SavedStateHandle()
        VisitEditViewModel(VisitEditMode.Edit(id), repository, repository, photos, handle) { today }.setNote("Changed")
        val restored = VisitEditViewModel(VisitEditMode.Edit(id), repository, repository, photos, handle) { today }
        assertTrue(restored.state.isEdit)
        assertEquals("Changed", restored.state.form.note)
        assertTrue(restored.state.hasUnsavedChanges)
        restored.save()
        assertEquals("Changed", stored(id)!!.note)
        assertEquals(1, repository.store.value.visits.count { it.id == id })
    }

    @Test
    fun loadErrors() {
        assertEquals(VisitEditStatus.NotFound, viewModel(VisitEditMode.Edit(12_345)).state.status)

        repository.observeFailure = IOException("db gone")
        val vm = add()
        assertEquals(VisitEditStatus.LoadFailed, vm.state.status)
        assertFalse(vm.state.canSave)
        vm.setFilmTitle("ignored")
        assertEquals("", vm.state.form.filmTitle)
        vm.requestClose()
        assertTrue(vm.state.isClosed)
    }

    @Test
    fun anUnknownHallStartsWithoutHall() {
        val vm = add(hallId = 77_777)
        assertEquals(VisitEditStatus.Ready, vm.state.status)
        assertNull(vm.state.form.hallId)
        assertFalse(vm.state.hasUnsavedChanges)
    }

    @Test
    fun datePickerMillisAreUtcMidnight() {
        val date = LocalDate.of(2026, 3, 29) // a DST change day in Berlin
        assertEquals(date, date.toPickerMillis().toPickerDate())
        assertEquals(0L, LocalDate.of(1970, 1, 1).toPickerMillis())
    }
}
