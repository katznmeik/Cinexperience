package io.github.katznmeik.cinexperience.feature.tickets.membership

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.ACCESSIBILITY_FONT_SCALES
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.core.testing.assertAccessible
import io.github.katznmeik.cinexperience.core.testing.fontScaleSuffix
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The membership card and its form at large font scales (tall captures), plus the
 * basic TalkBack checks of [assertAccessible]: the QR code is announced as
 * "Membership card QR code", the membership number letter by letter.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-port-night-xxhdpi")
class MembershipCardAccessibilityTest(private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = ACCESSIBILITY_FONT_SCALES.map { arrayOf(it) }
    }

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun check(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface(fontScale) { content() } }
        composeRule.waitForIdle()
        if (fontScale != 1f) composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${fontScaleSuffix(fontScale)}.png")
        composeRule.assertAccessible()
    }

    @Test
    fun card() = check("membership_card") { MembershipCardScreen(MembershipCardStates.shown(), onBack = {}) }

    @Test
    fun noCardYet() = check("membership_card_none") { MembershipCardScreen(MembershipCardUiState(MembershipCardContent.None), onBack = {}) }

    @Test
    fun newForm() = check("membership_card_form_new") { EditMembershipCardScreen(MembershipCardStates.newForm(), EditMembershipCardActions.None) }

    @Test
    fun filledForm() = check("membership_card_form") { EditMembershipCardScreen(MembershipCardStates.filledForm(), EditMembershipCardActions.None) }
}
