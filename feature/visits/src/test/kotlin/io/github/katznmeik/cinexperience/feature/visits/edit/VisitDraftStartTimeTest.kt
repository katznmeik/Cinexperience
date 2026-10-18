package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The visit form's saved state across the v1.4 update: a draft written before
 * `startTime` existed still restores (without a start time), and a draft with
 * one keeps it. Test data is invented.
 */
class VisitDraftStartTimeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())

    /** The fallback for a date a draft can't hold. */
    private val today = LocalDate.of(2026, 10, 15)

    /** A form draft exactly as builds before v1.4 wrote it: no `startTime` field. */
    private fun formJson(filmTitle: String) =
        """{"hallId":${SampleData.DELPHI_LUX_SAAL_1},"cinemaId":null,"date":"2026-10-12","filmTitle":"$filmTitle",""" +
            """"seatId":null,"format":"OMU","seatRating":4,"hallRatings":[5,0,0,0,0,0],"cinemaRatings":[0,0,0,0],""" +
            """"note":"Good sound","photoPath":null}"""

    /** An edit of visit 901 saved before v1.4, with an unsaved film title. */
    private val olderDraft =
        """{"form":${formJson("Anora")},"baseline":${formJson("Anor")},"visitId":901,"isEdit":true,""" +
            """"sessionPhotos":[],"pendingCapture":null}"""

    @Test
    fun aDraftWithoutAStartTimeDecodes() {
        val draft = VisitDraft.decodeOrNull(olderDraft)
        assertNotNull(draft)
        val form = draft!!.form.toForm(fallbackDate = today)
        assertNull(form.startTime)
        assertEquals("Anora", form.filmTitle)
        assertEquals(ScreeningFormat.OMU, form.format)
        assertEquals(Rating(4), form.seatRating)
        assertEquals("Good sound", form.note)
        assertEquals(LocalDate.of(2026, 10, 12), form.date)
        assertEquals("Anor", draft.baseline.toForm(fallbackDate = today).filmTitle)
    }

    @Test
    fun aDraftWithoutAStartTimeRestoresTheEditAndSaves() {
        val stored = Visit(id = 901, hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 10, 12), filmTitle = "Anor")
        repository.store.value = repository.store.value.copy(visits = repository.store.value.visits + stored)
        val handle = SavedStateHandle(mapOf(VisitEditViewModel.DRAFT_KEY to olderDraft))
        val vm = VisitEditViewModel(VisitEditMode.Edit(901), repository, repository, FakeVisitPhotoStore(), handle) {
            LocalDate.of(2026, 10, 17)
        }
        with(vm.uiState.value) {
            assertEquals(VisitEditStatus.Ready, status)
            assertEquals("Anora", form.filmTitle)
            assertNull(form.startTime)
        }
        vm.save()
        val saved = repository.store.value.visits.single { it.id == 901L }
        assertEquals("Anora", saved.filmTitle)
        assertNull(saved.startTime)
    }

    @Test
    fun aDraftKeepsItsStartTimeAndDropsAnInvalidOne() {
        val form = VisitForm(hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 10, 24), startTime = LocalTime.of(20, 15))
        val json = FormDraft.of(form).let { VisitDraft(it, it, 901, true, emptyList(), null).encode() }
        assertEquals(LocalTime.of(20, 15), VisitDraft.decodeOrNull(json)!!.form.toForm(fallbackDate = today).startTime)

        for (bad in listOf("25:00", "20:15:30", "8.15", "")) {
            val draft = VisitDraft.decodeOrNull(json.replace("\"20:15\"", "\"$bad\""))
            assertNull(bad, draft!!.form.toForm(fallbackDate = today).startTime)
        }
    }
}
