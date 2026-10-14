package io.github.katznmeik.cinexperience

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.ui.CinexApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen (Theme.Cinexperience.Starting) until the first frame; it then
        // switches to Theme.Cinexperience. The data loads in the background meanwhile.
        installSplashScreen()
        // Edge-to-edge with light icons on dark, transparent system bars (dark theme only).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as CinexApplication).container
        container.onAppStart()
        setContent {
            CinexTheme {
                CinexApp(container)
            }
        }
    }
}
