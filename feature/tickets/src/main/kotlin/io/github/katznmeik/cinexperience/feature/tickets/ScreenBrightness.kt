package io.github.katznmeik.cinexperience.feature.tickets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * While this is in the composition, the window shows at full brightness and the
 * screen stays on (spec §6.11: the ticket's QR code at the entrance). Both go back
 * to what they were as soon as it leaves the composition, whichever way the
 * screen is left: Back, the predictive back gesture, the activity ending or being
 * recreated. Several at once (e.g. two ticket screens during a transition) share
 * one hold on the window, see [ScreenBrightnessHold].
 */
@Composable
fun KeepScreenBrightAndOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val release = view.context.findActivity()?.window?.let(ScreenBrightnessHold::acquire)
        onDispose { release?.invoke() }
    }
}

/**
 * Full brightness and keep-screen-on for a window, counted: the first [acquire]
 * keeps the window's own brightness and keep-on flag, the last release puts them
 * back, so overlapping holds can't leave the window bright. Main thread only, like
 * every window change.
 */
internal object ScreenBrightnessHold {
    private class Hold(val brightness: Float, val keptOn: Boolean) {
        var count = 0
    }

    private val holds = WeakHashMap<Window, Hold>()

    /** Starts a hold on [window]; the returned function ends it (calling it again does nothing). */
    fun acquire(window: Window): () -> Unit {
        val hold = holds.getOrPut(window) {
            val attributes = window.attributes
            Hold(attributes.screenBrightness, attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0).also {
                window.attributes = attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        hold.count++
        var released = false
        return release@{
            if (released) return@release
            released = true
            hold.count--
            if (hold.count == 0 && holds[window] === hold) {
                holds.remove(window)
                window.attributes = window.attributes.apply { screenBrightness = hold.brightness }
                if (!hold.keptOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
