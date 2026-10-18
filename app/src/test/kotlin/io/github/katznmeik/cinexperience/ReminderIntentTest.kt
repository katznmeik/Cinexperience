package io.github.katznmeik.cinexperience

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.reminders.ReminderIntentToken
import io.github.katznmeik.cinexperience.reminders.ReminderIntents
import io.github.katznmeik.cinexperience.reminders.ReminderNotifier
import io.github.katznmeik.cinexperience.ui.FakeAppContainer
import io.github.katznmeik.cinexperience.ui.TopLevelDestination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A reminder's notification opens the visit, and its "Show ticket" the ticket (spec
 * §11.3 rule 7), through [MainActivity] with fakes ([TestCinexApplication]): once (not
 * again after recreation or from the recent apps), also when the activity runs already,
 * and only once a running backup import has ended. An unknown visit shows the screen's
 * message; an intent without a valid visit, or without the installation's token (another
 * app's), opens nothing; one for the screen shown already opens no second copy. Nothing
 * is written.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS, application = TestCinexApplication::class)
class ReminderIntentTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val backup = FakeBackupRepository()
    private val repository = FakeCinexRepository(SampleTickets.store())
    private val stored = repository.store.value
    private val visit = SampleTickets.VISIT
    private var activity: ActivityController<MainActivity>? = null

    /** The installation's token, as the notifications' intents carry it. */
    private val token: String? get() = ReminderIntentToken(context).current()

    private fun launch(intent: Intent, savedState: Bundle? = null): ActivityController<MainActivity> {
        TestCinexApplication.next = FakeAppContainer(repository, backupRepository = backup)
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

    private fun launcher() = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun waitForText(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun assertTheVisitIsOpen() {
        waitForText("EDIT VISIT")
        composeRule.onNodeWithText(SampleTickets.FILM).assertExists()
        composeRule.onNodeWithContentDescription("Start time, 20:15").assertExists()
    }

    private fun assertTheTicketIsOpen() {
        waitForText("TICKET")
        composeRule.onNodeWithContentDescription("Ticket QR code").assertExists()
    }

    private fun assertOnTheCinemasTab() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TopLevelDestination.Cinemas.testTag).assertExists()
        composeRule.onNodeWithText("EDIT VISIT").assertDoesNotExist()
        composeRule.onNodeWithText("TICKET").assertDoesNotExist()
    }

    private fun pressBack() {
        activity!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
    }

    @Test
    fun theNotificationOpensTheVisit() {
        launch(ReminderIntents.openVisit(context, visit, token))
        assertTheVisitIsOpen()
        pressBack()
        assertOnTheCinemasTab()
        assertEquals("nothing written", stored, repository.store.value)
    }

    @Test
    fun showTicketOpensTheTicketAndRemovesTheNotification() {
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        val tag = ReminderNotifier.tag(visit)
        val id = ReminderNotifier.id(ReminderKind.BEFORE_SHOWING)
        context.getSystemService(NotificationManager::class.java).notify(tag, id, Notification())
        launch(ReminderIntents.showTicket(context, visit, token))
        assertTheTicketIsOpen()
        assertNull(notifications.getNotification(tag, id))
        pressBack()
        assertOnTheCinemasTab()
        assertEquals(stored, repository.store.value)
    }

    @Test
    fun anUnknownVisitShowsTheScreensMessage() {
        launch(ReminderIntents.openVisit(context, 99_999, token))
        waitForText("This visit no longer exists.")
        finish()
        launch(ReminderIntents.showTicket(context, 99_999, token))
        waitForText("This ticket no longer exists.")
        assertEquals(stored, repository.store.value)
    }

    @Test
    fun anIntentWithoutAValidVisitOpensNothing() {
        for (intent in listOf(
            Intent(context, MainActivity::class.java).setAction(ReminderIntents.ACTION_OPEN_VISIT),
            ReminderIntents.openVisit(context, visit, token).putExtra(ReminderIntents.EXTRA_VISIT_ID, -4L),
            ReminderIntents.showTicket(context, visit, token).putExtra(ReminderIntents.EXTRA_VISIT_ID, "7001"),
        )) {
            launch(intent)
            assertOnTheCinemasTab()
            finish()
        }
    }

    @Test
    fun anotherAppsIntentWithoutTheTokenOpensNothing() {
        for (intent in listOf(
            // Straight to MainActivity, as any app could send it.
            Intent(context, MainActivity::class.java).setAction(ReminderIntents.ACTION_OPEN_VISIT).putExtra(ReminderIntents.EXTRA_VISIT_ID, visit),
            ReminderIntents.showTicket(context, visit, null),
            ReminderIntents.showTicket(context, visit, "0".repeat(32)),
            ReminderIntents.openVisit(context, visit, token!!.reversed()),
        )) {
            launch(intent)
            assertOnTheCinemasTab()
            finish()
        }
        // Not either when the app runs already.
        val launched = launch(launcher())
        launched.newIntent(ReminderIntents.openVisit(context, visit, null))
        assertOnTheCinemasTab()
    }

    @Test
    fun theFormShownAlreadyIsNotOpenedTwice() {
        val launched = launch(ReminderIntents.openVisit(context, visit, token))
        assertTheVisitIsOpen()
        launched.newIntent(ReminderIntents.openVisit(context, visit, token))
        composeRule.waitForIdle()
        assertTheVisitIsOpen()
        // One form: Back leads to the Cinemas tab.
        pressBack()
        assertOnTheCinemasTab()
    }

    @Test
    fun theTicketShownAlreadyIsNotOpenedTwiceButAnotherScreenOpensOnTop() {
        val launched = launch(ReminderIntents.showTicket(context, visit, token))
        assertTheTicketIsOpen()
        launched.newIntent(ReminderIntents.showTicket(context, visit, token))
        composeRule.waitForIdle()
        // The visit's form is another screen: it opens on top of the ticket.
        launched.newIntent(ReminderIntents.openVisit(context, visit, token))
        assertTheVisitIsOpen()
        pressBack()
        assertTheTicketIsOpen()
        pressBack()
        assertOnTheCinemasTab()
    }

    @Test
    fun theVisitIsNotOpenedAgainAfterRecreation() {
        val launched = launch(ReminderIntents.openVisit(context, visit, token))
        assertTheVisitIsOpen()
        launched.recreate()
        assertTheVisitIsOpen()
        // Opened once: Back leads to the Cinemas tab.
        pressBack()
        assertOnTheCinemasTab()
    }

    @Test
    fun anIntentFromTheRecentAppsIsNotOpenedAgain() {
        launch(ReminderIntents.openVisit(context, visit, token).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertOnTheCinemasTab()
    }

    @Test
    fun aReminderTappedWhileTheAppRunsOpensOnTop() {
        val launched = launch(launcher())
        assertOnTheCinemasTab()
        launched.newIntent(ReminderIntents.showTicket(context, visit, token))
        assertTheTicketIsOpen()
        pressBack()
        assertOnTheCinemasTab()
    }

    @Test
    fun aReminderWaitsForARunningBackupImport() {
        backup.isImporting.value = true
        launch(ReminderIntents.openVisit(context, visit, token))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("EDIT VISIT").assertDoesNotExist()
        backup.isImporting.value = false
        assertTheVisitIsOpen()
    }
}
