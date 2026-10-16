package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditEntry
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** "Delete visit" asks first: Delete removes the visit and its photo and closes, Cancel keeps everything. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class VisitDeleteDialogUiTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val repository = FakeCinexRepository(VisitFixtures.store())
    private val photos = FakeVisitPhotoStore(setOf(PHOTO))
    private var closed = false

    private fun storedVisit() = repository.store.value.visits.find { it.id == VisitFixtures.EDITED_VISIT }

    /** Opens the saved visit and taps "Delete visit". */
    private fun openDeleteDialog() {
        val vm = VisitEditViewModel(
            VisitEditMode.Edit(VisitFixtures.EDITED_VISIT),
            repository,
            repository,
            photos,
            SavedStateHandle(),
        ) { LocalDate.of(2026, 10, 14) }
        composeRule.setContent { CinexTheme { VisitEditEntry(vm, photos, onClose = { closed = true }) } }
        composeRule.onNodeWithText("Delete visit").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete this visit?").assertExists()
    }

    @Test
    fun deleteRemovesTheVisitAndItsPhotoAndCloses() {
        openDeleteDialog()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        assertTrue(closed)
        assertNull(storedVisit())
        assertEquals(listOf(PHOTO), photos.deleted)
    }

    @Test
    fun cancelKeepsTheVisitAndTheForm() {
        openDeleteDialog()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete this visit?").assertDoesNotExist()
        composeRule.onNodeWithText("EDIT VISIT").assertExists()
        assertFalse(closed)
        assertNotNull(storedVisit())
        assertTrue(photos.deleted.isEmpty())
    }

    private companion object {
        const val PHOTO = "photos/visit-fixture.jpg"
    }
}
