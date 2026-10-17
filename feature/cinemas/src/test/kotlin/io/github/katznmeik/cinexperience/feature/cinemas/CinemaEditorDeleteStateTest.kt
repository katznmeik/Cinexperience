package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.cinemas.edit.CinemaDeleteState
import io.github.katznmeik.cinexperience.feature.cinemas.edit.CinemaEditor
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditMessages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editor's [CinemaEditor.deleteState]: how the cinema detail closes itself around its own delete. */
class CinemaEditorDeleteStateTest {

    private val repository = FakeCinexRepository(SampleData.store())

    private suspend fun confirmDelete(editor: CinemaEditor, cinemaId: Long) {
        editor.requestDeleteCinema(repository.observeCinemaDetail(cinemaId).first()!!)
        editor.confirm()
    }

    @Test
    fun aTrackedDeleteRunsAndEndsDoneWithItsMessage() = runTest {
        val cinemaId = repository.createCinema("Sommerkino")
        val editor = CinemaEditor(this, repository, repository, tracksCinemaDelete = true)

        confirmDelete(editor, cinemaId)
        assertEquals(CinemaDeleteState.Running, editor.deleteState.value)
        advanceUntilIdle()

        assertEquals(CinemaDeleteState.Done(EditMessages.deleted("Sommerkino")), editor.deleteState.value)
        assertNull(editor.message.value)
    }

    @Test
    fun aFailedTrackedDeleteGoesBackToNone() = runTest {
        val cinemaId = repository.createCinema("Sommerkino")
        val editor = CinemaEditor(this, repository, repository, tracksCinemaDelete = true)
        repository.failure = IllegalStateException("io")

        confirmDelete(editor, cinemaId)
        advanceUntilIdle()

        assertEquals(CinemaDeleteState.None, editor.deleteState.value)
        assertEquals(EditMessages.DELETE_FAILED, editor.message.value)
    }

    @Test
    fun aDeleteOfAnAlreadyDeletedCinemaGoesBackToNone() = runTest {
        val cinemaId = repository.createCinema("Sommerkino")
        val editor = CinemaEditor(this, repository, repository, tracksCinemaDelete = true)
        editor.requestDeleteCinema(repository.observeCinemaDetail(cinemaId).first()!!)
        repository.deleteCinema(cinemaId)

        editor.confirm()
        advanceUntilIdle()

        assertEquals(CinemaDeleteState.None, editor.deleteState.value)
        assertEquals(EditMessages.GONE, editor.message.value)
    }

    @Test
    fun anUntrackedDeleteOnlyShowsItsMessage() = runTest {
        val cinemaId = repository.createCinema("Sommerkino")
        val editor = CinemaEditor(this, repository, repository)

        confirmDelete(editor, cinemaId)
        assertEquals(CinemaDeleteState.None, editor.deleteState.value)
        advanceUntilIdle()

        assertEquals(CinemaDeleteState.None, editor.deleteState.value)
        assertEquals(EditMessages.deleted("Sommerkino"), editor.message.value)
    }
}
