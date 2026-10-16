package io.github.katznmeik.cinexperience.core.testing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Self-tests of [assertAccessible]: it catches what it promises to catch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class AccessibilityChecksTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            ScreenshotSurface {
                Text("Title", Modifier.semantics { heading() })
                Box(Modifier.padding(top = 80.dp)) { content() }
            }
        }
    }

    private fun assertProblem(fragment: String) {
        val error = assertThrows(AssertionError::class.java) { composeRule.assertAccessible() }
        assertTrue(error.message, error.message!!.contains(fragment))
    }

    @Test
    fun paddingAroundASmallClickableIsNotATouchTarget() {
        show { Box(Modifier.padding(14.dp).size(20.dp).clickable {}.semantics { contentDescription = "Tiny" }) }
        assertProblem("Touch target smaller than 48.0.dp: \"Tiny\"")
    }

    @Test
    fun smallClickableFails() {
        show { Box(Modifier.size(36.dp).clickable {}.semantics { contentDescription = "Small" }) }
        assertProblem("Touch target smaller than 48.0.dp: \"Small\"")
    }

    @Test
    fun materialMinimumInteractiveSizePasses() {
        show {
            Box(Modifier.minimumInteractiveComponentSize().size(36.dp).clickable {}.semantics { contentDescription = "Chip" })
        }
        composeRule.assertAccessible()
    }

    @Test
    fun iconButtonWithDescriptionPasses() {
        show { IconButton(onClick = {}) { Icon(CinexIcons.Close, contentDescription = "Close") } }
        composeRule.assertAccessible()
    }

    @Test
    fun unlabelledClickableFails() {
        show { Box(Modifier.size(48.dp).clickable {}) }
        assertProblem("Clickable element without a label")
    }

    @Test
    fun descriptionRepeatingTheTextFails() {
        show {
            Box(Modifier.size(48.dp).clickable {}.semantics { contentDescription = "Add photo" }) { Text("Add photo") }
        }
        assertProblem("Announced twice: add photo")
    }

    @Test
    fun allowedRepeatedTextPasses() {
        show {
            Box(Modifier.size(48.dp).clickable {}.semantics { contentDescription = "Add photo" }) { Text("Add photo") }
        }
        composeRule.assertAccessible(allowRepeated = setOf("add photo"))
    }

    @Test
    fun screenWithoutHeadingFails() {
        composeRule.setContent { ScreenshotSurface { Text("Just text") } }
        assertProblem("No heading on the screen")
    }
}
