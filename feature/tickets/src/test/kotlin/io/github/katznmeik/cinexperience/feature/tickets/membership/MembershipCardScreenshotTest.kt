package io.github.katznmeik.cinexperience.feature.tickets.membership

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleMembershipCard
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the membership card (spec §6.12) and its form (§6.13) with the
 * INVENTED card of [SampleMembershipCard], phone-sized, dark theme. PNGs land in
 * feature/tickets/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class MembershipCardScreenshotTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var edits = 0
    private var removes = 0

    private fun captureCard(name: String, state: MembershipCardUiState) {
        composeRule.setContent {
            ScreenshotSurface { MembershipCardScreen(state, onBack = {}, onEdit = { edits++ }, onRemove = { removes++ }) }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun captureForm(name: String, state: EditMembershipCardUiState) {
        composeRule.setContent { ScreenshotSurface { EditMembershipCardScreen(state, EditMembershipCardActions.None) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun card() {
        captureCard("membership_card", MembershipCardStates.shown())
        composeRule.onNodeWithContentDescription(CARD_QR_DESCRIPTION).assertExists()
        composeRule.onNodeWithText(SampleMembershipCard.NAME).assertExists()
        composeRule.onNodeWithContentDescription("Membership number, T E S T - 0 0 4 2").assertExists()
        composeRule.onNodeWithContentDescription("Membership, Yorck Unlimited").assertExists()
        composeRule.onNodeWithContentDescription("Edit card").performClick()
        assertEquals(1, edits)
    }

    /** A card without typed texts: only the QR code. */
    @Test
    fun cardWithoutTexts() {
        captureCard("membership_card_qr_only", MembershipCardUiState(MembershipCardContent.Shown(MembershipCard(null, null, null, SampleMembershipCard.qrModules))))
        composeRule.onNodeWithText("Membership number").assertDoesNotExist()
    }

    @Test
    fun noCardYet() {
        captureCard("membership_card_none", MembershipCardUiState(MembershipCardContent.None))
        composeRule.onNodeWithText(NO_CARD_TEXT).assertExists()
        composeRule.onNodeWithContentDescription("Edit card").assertDoesNotExist()
        composeRule.onNodeWithText("Add membership card").performClick()
        assertEquals(1, edits)
    }

    @Test
    fun confirmRemove() {
        captureCard("membership_card_remove", MembershipCardStates.shown().copy(confirmRemove = true))
        composeRule.onNodeWithText(REMOVE_CARD_TEXT).assertExists()
    }

    @Test
    fun newForm() {
        captureForm("membership_card_form_new", MembershipCardStates.newForm())
        composeRule.onNodeWithText(SCREENSHOT_HINT).assertExists()
        composeRule.onNodeWithText("Choose screenshot").assertExists()
    }

    @Test
    fun formWithQrCode() {
        captureForm("membership_card_form", MembershipCardStates.filledForm())
        composeRule.onNodeWithContentDescription(CARD_QR_DESCRIPTION).assertExists()
        composeRule.onNodeWithText("Choose another screenshot").assertExists()
    }

    @Test
    fun formReading() {
        captureForm("membership_card_form_reading", MembershipCardStates.newForm().copy(isReading = true))
        composeRule.onNodeWithText("Reading the screenshot…").assertExists()
    }
}

/** Screen states with the invented card. */
internal object MembershipCardStates {
    fun shown(): MembershipCardUiState = MembershipCardUiState(MembershipCardContent.Shown(SampleMembershipCard.card))

    fun newForm(): EditMembershipCardUiState = EditMembershipCardUiState(isLoading = false, isNew = true, membershipType = "Yorck Unlimited")

    fun filledForm(): EditMembershipCardUiState = EditMembershipCardUiState(
        isLoading = false,
        isNew = false,
        qrModules = SampleMembershipCard.qrModules,
        name = SampleMembershipCard.NAME,
        memberNumber = SampleMembershipCard.MEMBER_NUMBER,
        membershipType = SampleMembershipCard.MEMBERSHIP_TYPE,
    )
}
