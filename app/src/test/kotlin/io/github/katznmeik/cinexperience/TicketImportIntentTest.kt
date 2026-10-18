package io.github.katznmeik.cinexperience

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketImportError
import io.github.katznmeik.cinexperience.ui.FakeAppContainer
import io.github.katznmeik.cinexperience.ui.TopLevelDestination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Share" and "Open with" a ticket PDF (spec §6.10) through [MainActivity] with fakes
 * ([TestCinexApplication]): both open the import, which replaces itself with Add visit;
 * other addresses and this app's own providers get the "can't open" message; an intent
 * is imported once (not again after recreation or from the recent apps), also when the
 * activity is reused; and a share waits for a running backup import.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS, application = TestCinexApplication::class)
class TicketImportIntentTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val files = FakeTicketFiles()
    private val backup = FakeBackupRepository()
    private val repository = FakeCinexRepository(SampleTickets.store().copy(visits = emptyList(), tickets = emptyList()))
    private val attachment = Uri.parse("content://com.example.mail.attachments/ticket/1")
    private var activity: ActivityController<MainActivity>? = null

    /** Starts [MainActivity] with [intent] (Robolectric runs its lifecycle at once, on this thread). */
    private fun launch(intent: Intent, savedState: Bundle? = null): ActivityController<MainActivity> {
        TestCinexApplication.next = FakeAppContainer(repository, backupRepository = backup, ticketFileSource = files, ticketReader = FakeTicketReader)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        if (savedState == null) controller.setup() else controller.setup(savedState)
        activity = controller
        return controller
    }

    private fun finish() {
        activity?.pause()?.stop()?.destroy()
        activity = null
    }

    @After
    fun tearDown() {
        finish()
        TestCinexApplication.next = null
    }

    private fun share(uri: Uri?) = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/pdf").apply {
        if (uri != null) putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun openWith(uri: Uri) = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")

    private fun launcher() = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun waitForText(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun assertAddVisitWithTheTicket() {
        waitForText("ADD VISIT")
        composeRule.onNodeWithText(SampleTickets.FILM).assertExists()
        composeRule.onNodeWithContentDescription("Booking number, T E S T 1 2 3").assertExists()
        // Every seat came through the route as printed: a row without a name and a fixed label too.
        for (seat in listOf("Row 7, Seat 10", "Row 7, Seat 11", "Seat 12", "Row 8, Seat R")) {
            assertTrue(seat, composeRule.onAllNodesWithContentDescription(seat).fetchSemanticsNodes().isNotEmpty())
        }
        composeRule.onNodeWithText("QR code read from the ticket.").assertExists()
        composeRule.onNodeWithText("IMPORT TICKET").assertDoesNotExist()
    }

    private fun assertOnTheCinemasTab() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TopLevelDestination.Cinemas.testTag).assertExists()
        composeRule.onNodeWithText("ADD VISIT").assertDoesNotExist()
        composeRule.onNodeWithText("IMPORT TICKET").assertDoesNotExist()
    }

    private fun pressBack() {
        activity!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
    }

    @Test
    fun aSharedTicketOpensAddVisitInPlaceOfTheImport() {
        launch(share(attachment))
        assertAddVisitWithTheTicket()
        assertEquals(listOf(attachment.toString()), files.opened.toList())
        // The import screen was replaced: Back leaves Add visit for the Cinemas tab.
        pressBack()
        assertOnTheCinemasTab()
        assertTrue("nothing stored", repository.store.value.tickets.isEmpty())
    }

    @Test
    fun openWithImportsTheFileToo() {
        launch(openWith(attachment))
        assertAddVisitWithTheTicket()
    }

    @Test
    fun anotherAppsFileAddressIsRefusedUnread() {
        launch(share(Uri.parse("file:///sdcard/Download/ticket.pdf")))
        waitForText(TicketImportError.OPEN_FAILED.message)
        assertTrue("nothing was opened", files.opened.isEmpty())
    }

    @Test
    fun theAppsOwnFilesAreRefusedUnread() {
        launch(openWith(Uri.parse("content://${context.packageName}.photos/photos/visit-1.jpg")))
        waitForText(TicketImportError.OPEN_FAILED.message)
        assertTrue("nothing was opened", files.opened.isEmpty())
    }

    @Test
    fun aShareWithoutAFileCantBeRead() {
        launch(share(null))
        waitForText(TicketImportError.OPEN_FAILED.message)
        assertTrue(files.opened.isEmpty())
    }

    @Test
    fun theIntentIsNotImportedAgainAfterRecreation() {
        val launched = launch(share(attachment))
        assertAddVisitWithTheTicket()
        launched.recreate()
        assertAddVisitWithTheTicket()
        assertEquals("read once", 1, files.opened.size)
        pressBack()
        assertOnTheCinemasTab()
    }

    @Test
    fun anIntentFromTheRecentAppsIsNotImportedAgain() {
        launch(share(attachment).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertOnTheCinemasTab()
        assertTrue(files.opened.isEmpty())
    }

    @Test
    fun aTicketSharedToTheRunningActivityIsImported() {
        val launched = launch(launcher())
        assertOnTheCinemasTab()
        launched.newIntent(share(attachment))
        assertAddVisitWithTheTicket()
    }

    @Test
    fun aShareWaitsForARunningBackupImport() {
        backup.isImporting.value = true
        launch(share(attachment))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("IMPORT TICKET").assertDoesNotExist()
        composeRule.onNodeWithText("ADD VISIT").assertDoesNotExist()
        assertTrue(files.opened.isEmpty())

        backup.isImporting.value = false
        assertAddVisitWithTheTicket()
    }
}
