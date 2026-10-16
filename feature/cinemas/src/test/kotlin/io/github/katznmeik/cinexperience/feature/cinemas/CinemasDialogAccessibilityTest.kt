package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.detail.hallMenuEntries
import io.github.katznmeik.cinexperience.feature.cinemas.edit.CinemaFormFields
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialog
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogActions
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditMessages
import io.github.katznmeik.cinexperience.feature.cinemas.edit.ItemMenu
import io.github.katznmeik.cinexperience.feature.cinemas.edit.NameField
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cinema and hall dialogs and the item menu at font scales 1, 1.5 and 2,
 * with the TalkBack checks. The font scale is set through the configuration
 * (not ScreenshotSurface), so the dialog and menu windows get it too; the clock
 * is held, so their animations can't keep Compose busy. The screen captures
 * include every window. The form dialogs' fields are checked on their own.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class CinemasDialogAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = FakeCinexRepository(SampleData.store())

    private val noDialogActions = object : EditDialogActions {
        override fun onNameChange(name: String) = Unit
        override fun onAddressChange(address: String) = Unit
        override fun onNotesChange(notes: String) = Unit
        override fun onConfirm() = Unit
        override fun onDismiss() = Unit
        override fun onDeleteCinema() = Unit
    }

    private fun checkWindow(name: String, requireHeading: Boolean = true, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ScreenshotSurface { content() } }
        composeRule.mainClock.advanceTimeBy(2_000)
        if (fontScale != 1f) captureScreenRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible(requireHeading)
    }

    /** [dialog] over Manage cinemas & halls. */
    private fun checkDialog(name: String, dialog: EditDialog) {
        val cinemas = runBlocking { repository.observeCinemaDetails().first() }
        checkWindow(name) {
            ManageCinemasScreen(
                state = ManageCinemasUiState(isLoading = false, cinemas = cinemas, dialog = dialog),
                onBack = {},
                onOpenCinema = {},
                onAddCinema = {},
                onEditCinema = {},
                onDeleteCinema = {},
                onAddHall = {},
                onRenameHall = {},
                onDeleteHall = {},
                dialogActions = noDialogActions,
                onMessageShown = {},
            )
        }
    }

    /**
     * The fields of a form dialog on the dialog's surface. An AlertDialog with a
     * text field never lets Robolectric go idle, not even with the clock held, so
     * the fields are checked on their own (like "Save filter" in feature:program).
     */
    private fun checkFields(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        composeRule.setContent {
            ScreenshotSurface {
                Box(Modifier.background(CinexTheme.colors.surfaceRaised).padding(24.dp)) { content() }
            }
        }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible(requireHeading = false)
    }

    @Test
    fun addCinemaFields() = checkFields("dialog_add_cinema_fields") { CinemaFormFields(EditDialog.CinemaForm(cinemaId = null), noDialogActions) }

    @Test
    fun editCinemaFields() = checkFields("dialog_edit_cinema_fields") {
        CinemaFormFields(
            EditDialog.CinemaForm(
                cinemaId = 99,
                name = "Atelier",
                address = "Example Street 1, Berlin",
                notes = "Small, quiet, good sound.",
                canDelete = true,
            ),
            noDialogActions,
        )
    }

    /** The name field empty, filled (renaming a hall) and with its error, the message in the text colour. */
    @Test
    fun nameField() = checkFields("dialog_name_field") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NameField("", null) {}
            NameField("Saal 1", null) {}
            NameField("", EditMessages.NAME_REQUIRED) {}
        }
    }

    @Test
    fun deleteCinema() = checkDialog("dialog_delete_cinema", EditDialog.ConfirmDeleteCinema(99, "Atelier", hallCount = 2, visitCount = 3))

    @Test
    fun deleteHall() = checkDialog("dialog_delete_hall", EditDialog.ConfirmDeleteHall(2, "Saal 2", visitCount = 1))

    /** A preloaded hall's menu: "Delete" is disabled and says why. */
    @Test
    fun hallMenu() {
        val hall = runBlocking { repository.observeCinemaDetail(SampleData.DELPHI_LUX).first()!! }.halls.first()
        checkWindow("item_menu_hall", requireHeading = false) {
            ItemMenu(expanded = true, onDismiss = {}, entries = hallMenuEntries(hall, onRename = {}, onDelete = {}))
        }
    }
}
