package io.github.katznmeik.cinexperience.feature.cinemas.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditMessages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A cinema deleted from its own detail screen closes that screen, also when the
 * delete ends while the screen isn't resumed (the app paused, a transition running).
 * The close goes through [dropUnlessResumedWith], as in `cinemaDetailScreen`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class CinemaDeleteCloseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = FakeCinexRepository(SampleData.store())

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = TestLifecycleOwner()

    private fun moveTo(state: Lifecycle.State) {
        composeRule.runOnUiThread { owner.registry.currentState = state }
        composeRule.waitForIdle()
    }

    /** Shows the detail of a new user-added cinema and opens its delete confirmation. */
    private fun showAndAskToDelete(closed: MutableList<String>): CinemaDetailViewModel {
        val cinemaId = runBlocking { repository.createCinema("Sommerkino") }
        val viewModel = CinemaDetailViewModel(cinemaId, repository, repository, FakeProgramRepository())
        moveTo(Lifecycle.State.RESUMED)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CinexTheme {
                    CinemaDetailEntry(
                        viewModel,
                        onBack = {},
                        onOpenHall = {},
                        onCinemaDeleted = dropUnlessResumedWith { message: String -> closed += message },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        viewModel.editCinema()
        viewModel.deleteFromForm()
        composeRule.waitForIdle()
        return viewModel
    }

    @Test
    fun aDeleteWhileResumedClosesTheScreenRightAway() {
        val closed = mutableListOf<String>()
        val viewModel = showAndAskToDelete(closed)

        viewModel.confirmDialog()
        composeRule.waitForIdle()
        assertEquals(listOf(EditMessages.deleted("Sommerkino")), closed)
    }

    @Test
    fun aDeleteThatEndsWhileTheScreenIsPausedClosesItOnceItIsResumed() {
        val closed = mutableListOf<String>()
        val viewModel = showAndAskToDelete(closed)

        moveTo(Lifecycle.State.STARTED)
        viewModel.confirmDialog()
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), closed)

        moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf(EditMessages.deleted("Sommerkino")), closed)
    }
}
