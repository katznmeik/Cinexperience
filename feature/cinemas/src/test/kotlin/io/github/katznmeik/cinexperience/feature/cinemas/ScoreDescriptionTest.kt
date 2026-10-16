package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailContent
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailScreen
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailUiState
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogActions
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaSort
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasUiState
import io.github.katznmeik.cinexperience.feature.cinemas.list.sortedFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A score is read "score 4.2" or "not rated", not as a bare "4.2" or "–", and only once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class ScoreDescriptionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = FakeCinexRepository(SampleData.store())

    @Test
    fun texts() {
        assertEquals("score 4.3", scoreDescription(4.25))
        assertEquals("score 4.0", scoreDescription(4.0))
        assertEquals("not rated", scoreDescription(null))
    }

    private fun SemanticsNode.descriptions(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** The merged announcement of the row holding [title] has the spoken score instead of the shown one. */
    private fun assertSpokenScore(title: String, score: Double?) {
        val row = composeRule.onNodeWithText(title).fetchSemanticsNode()
        assertTrue("${row.descriptions()}", scoreDescription(score) in row.descriptions())
        assertFalse("${row.texts()}", formatScore(score) in row.texts())
    }

    @Test
    fun cinemaCards() {
        val cinemas = runBlocking { repository.observeCinemas().first() }
        val rated = cinemas.first { it.overallScore != null }
        val unrated = cinemas.first { it.overallScore == null }
        val state = CinemasUiState(isLoading = false, sort = CinemaSort.NAME, cinemas = cinemas.sortedFor(CinemaSort.NAME))
        composeRule.setContent { ScreenshotSurface { CinemasScreen(state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {}) } }
        composeRule.waitForIdle()
        assertSpokenScore(rated.cinema.name.uppercase(), rated.overallScore)
        assertSpokenScore(unrated.cinema.name.uppercase(), null)
    }

    private fun showDetail(detail: CinemaDetail) {
        composeRule.setContent {
            ScreenshotSurface {
                CinemaDetailScreen(
                    state = CinemaDetailUiState(CinemaDetailContent.Loaded(detail)),
                    onBack = {},
                    onEdit = {},
                    onAddHall = {},
                    onOpenHall = {},
                    onRenameHall = {},
                    onDeleteHall = {},
                    dialogActions = object : EditDialogActions {
                        override fun onNameChange(name: String) = Unit
                        override fun onAddressChange(address: String) = Unit
                        override fun onNotesChange(notes: String) = Unit
                        override fun onConfirm() = Unit
                        override fun onDismiss() = Unit
                        override fun onDeleteCinema() = Unit
                    },
                    onMessageShown = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun hallRows() {
        val detail = runBlocking { repository.observeCinemaDetail(SampleData.DELPHI_LUX).first()!! }
        val rated = detail.halls.first { it.overallScore != null }
        val unrated = detail.halls.first { it.overallScore == null }
        showDetail(detail)
        assertSpokenScore(rated.hall.name, rated.overallScore)
        assertSpokenScore(unrated.hall.name, null)
    }

    /** The cinema page's score card uses the same wording: "Overall score 4.2", "Popcorn quality not rated". */
    @Test
    fun scoreCard() {
        val detail = runBlocking { repository.observeCinemaDetail(SampleData.BABYLON).first()!! }
        val rated = CinemaCategory.entries.first { detail.scores[it] != null }
        val unrated = CinemaCategory.entries.first { detail.scores[it] == null }
        showDetail(detail)
        composeRule.onNodeWithContentDescription("Overall score ${formatScore(detail.scores.overall)}").assertExists()
        composeRule.onNodeWithContentDescription("${rated.displayName} score ${formatScore(detail.scores[rated]!!.average)}").assertExists()
        composeRule.onNodeWithContentDescription("${unrated.displayName} not rated").assertExists()
    }
}
