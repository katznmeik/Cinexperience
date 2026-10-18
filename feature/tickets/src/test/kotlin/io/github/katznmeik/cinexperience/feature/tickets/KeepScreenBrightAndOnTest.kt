package io.github.katznmeik.cinexperience.feature.tickets

import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ticket screen's full brightness and keep-screen-on (spec §6.11) come and go with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeepScreenBrightAndOnTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val window get() = composeRule.activity.window
    private val brightness get() = window.attributes.screenBrightness
    private val keptOn get() = window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun brightAndOnWhileShownAndRestoredWhenLeft() {
        assertEquals(BRIGHTNESS_OVERRIDE_NONE, brightness)
        assertFalse(keptOn)
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) KeepScreenBrightAndOn() }
        composeRule.waitForIdle()
        assertEquals(BRIGHTNESS_OVERRIDE_FULL, brightness)
        assertTrue(keptOn)

        shown = false
        composeRule.waitForIdle()
        assertEquals(BRIGHTNESS_OVERRIDE_NONE, brightness)
        assertFalse(keptOn)
    }

    @Test
    fun theWindowsOwnSettingsComeBack() {
        composeRule.runOnUiThread {
            window.attributes = window.attributes.apply { screenBrightness = 0.3f }
            window.addFlags(FLAG_KEEP_SCREEN_ON)
        }
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) KeepScreenBrightAndOn() }
        composeRule.waitForIdle()
        assertEquals(BRIGHTNESS_OVERRIDE_FULL, brightness)

        shown = false
        composeRule.waitForIdle()
        assertEquals(0.3f, brightness)
        assertTrue("the window kept the screen on before, so it still does", keptOn)
    }

    @Test
    fun overlappingScreensRestoreOnlyWhenTheLastLeaves() {
        // E.g. a ticket opened on top of a ticket: the first leaves while the second is shown.
        var first by mutableStateOf(true)
        var second by mutableStateOf(false)
        composeRule.setContent {
            if (first) KeepScreenBrightAndOn()
            if (second) KeepScreenBrightAndOn()
        }
        composeRule.waitForIdle()
        second = true
        composeRule.waitForIdle()
        first = false
        composeRule.waitForIdle()
        assertEquals(BRIGHTNESS_OVERRIDE_FULL, brightness)
        assertTrue(keptOn)

        second = false
        composeRule.waitForIdle()
        assertEquals(BRIGHTNESS_OVERRIDE_NONE, brightness)
        assertFalse(keptOn)
    }

    @Test
    fun aReleaseRunsOnce() {
        val releaseFirst = ScreenBrightnessHold.acquire(window)
        val releaseSecond = ScreenBrightnessHold.acquire(window)
        releaseFirst()
        releaseFirst() // a second call must not end the other hold
        assertEquals(BRIGHTNESS_OVERRIDE_FULL, brightness)
        releaseSecond()
        assertEquals(BRIGHTNESS_OVERRIDE_NONE, brightness)
        assertFalse(keptOn)
    }
}
