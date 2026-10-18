package io.github.katznmeik.cinexperience.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.katznmeik.cinexperience.FakeTicketFiles
import io.github.katznmeik.cinexperience.FakeTicketReader
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.tickets.ImportTicketRoute
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ticket import in the app's navigation (spec §6.10): a ticket imported before
 * opens its visit with "This ticket was imported before" (its removed QR code stored
 * again), and Settings → Data → Import ticket picks a PDF with the system file picker.
 * Fakes and invented data only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class TicketImportNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val files = FakeTicketFiles()
    private val attachment = Uri.parse("content://com.example.mail.attachments/ticket/1")

    private fun waitForText(text: String, substring: Boolean = false) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun showApp(repository: FakeCinexRepository, pending: ImportTicketRoute? = null) {
        val container = FakeAppContainer(repository, ticketFileSource = files, ticketReader = FakeTicketReader)
        val pendingImport = mutableStateOf(pending)
        composeRule.setContent {
            CinexTheme { CinexApp(container, pendingTicketImport = pendingImport.value, onPendingTicketImportOpened = { pendingImport.value = null }) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aTicketImportedBeforeOpensItsVisitAndGetsItsQrCodeBack() {
        val repository = FakeCinexRepository(SampleTickets.store(tickets = listOf(SampleTickets.ticket(qr = null))))
        showApp(repository, ImportTicketRoute(attachment.toString()))
        waitForText("EDIT VISIT")
        waitForText("This ticket was imported before.")
        assertEquals(FakeTicketReader.QR, repository.store.value.tickets.single().qrModules)
        assertEquals("no second visit", 1, repository.store.value.visits.count { it.filmTitle == SampleTickets.FILM })

        // The import was replaced: Back returns to the Cinemas tab.
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("EDIT VISIT").assertDoesNotExist()
        composeRule.onNodeWithText("IMPORT TICKET").assertDoesNotExist()
        composeRule.onNodeWithTag(TopLevelDestination.Cinemas.testTag).assertExists()
    }

    @Test
    fun settingsImportTicketPicksAPdfAndImportsIt() {
        val repository = FakeCinexRepository(SampleTickets.store().copy(visits = emptyList(), tickets = emptyList()))
        showApp(repository)
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Import ticket").performScrollTo().performClick()
        composeRule.waitForIdle()

        val picker = shadowOf(composeRule.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        assertEquals(listOf("application/pdf"), picker.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertTrue("no persistable permission asked for", picker.intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0)

        shadowOf(composeRule.activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(attachment))
        waitForText("ADD VISIT")
        composeRule.onNodeWithContentDescription("Booking number, T E S T 1 2 3").assertExists()
        assertEquals(listOf(attachment.toString()), files.opened.toList())
    }

    /** An address too long to read (only a misbehaving picker) opens the import, which says it can't open the file. */
    @Test
    fun aPickedAddressTooLongToReadSaysItCantBeOpened() {
        showApp(FakeCinexRepository(SampleTickets.store()))
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Import ticket").performScrollTo().performClick()
        composeRule.waitForIdle()
        val picker = shadowOf(composeRule.activity).nextStartedActivityForResult
        val tooLong = Uri.parse(attachment.toString() + "1".repeat(TicketUris.MAX_LENGTH))
        shadowOf(composeRule.activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(tooLong))
        waitForText("Couldn't open the file. Share or pick the ticket PDF again.")
        composeRule.onNodeWithText("IMPORT TICKET").assertExists()
        assertTrue("never opened", files.opened.isEmpty())
    }

    @Test
    fun cancellingThePickerDoesNothing() {
        showApp(FakeCinexRepository(SampleTickets.store()))
        composeRule.onNodeWithTag(TopLevelDestination.Settings.testTag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Import ticket").performScrollTo().performClick()
        composeRule.waitForIdle()
        val picker = shadowOf(composeRule.activity).nextStartedActivityForResult
        shadowOf(composeRule.activity).receiveResult(picker.intent, Activity.RESULT_CANCELED, null)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("IMPORT TICKET").assertDoesNotExist()
        composeRule.onNodeWithText("Import ticket").assertExists()
        assertTrue(files.opened.isEmpty())
    }
}
