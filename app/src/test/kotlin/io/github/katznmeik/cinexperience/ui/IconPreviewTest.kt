package io.github.katznmeik.cinexperience.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.R
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Previews of the three launcher icon concepts for the owner to choose from
 * (`res/values/icon.xml` picks one): each concept under a circle and a squircle
 * launcher mask, as a themed (monochrome) icon on a light and a dark wallpaper,
 * and at home-screen size. PNGs: app/build/outputs/roborazzi/icon_concept_<n>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h400dp-port-night-xxhdpi")
class IconPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun preview(number: Int, @DrawableRes mark: Int, title: String) {
        composeRule.setContent { CinexTheme { IconSheet(mark, "Concept $number · $title") } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/icon_concept_$number.png")
    }

    @Test
    fun concept1() = preview(1, R.drawable.ic_launcher_concept_1, "Screen & seats")

    @Test
    fun concept2() = preview(2, R.drawable.ic_launcher_concept_2, "Favourite seat")

    @Test
    fun concept3() = preview(3, R.drawable.ic_launcher_concept_3, "C of seat rows")
}

/** Launcher wallpaper behind the previews (not an app colour). */
private val Wallpaper = Color(0xFF55606B)
private val ThemedLightBackground = Color(0xFFDDE3EA)
private val ThemedLightMark = Color(0xFF2B3440)
private val ThemedDarkBackground = Color(0xFF2B3440)
private val ThemedDarkMark = Color(0xFFDDE3EA)

@Composable
private fun IconSheet(@DrawableRes mark: Int, title: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Wallpaper)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(title, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            AdaptiveIcon(mark, CircleShape, 170.dp)
            AdaptiveIcon(mark, SquircleShape, 170.dp)
        }
        Text("Themed icon (Android 13+) · home-screen size", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AdaptiveIcon(mark, CircleShape, 84.dp, ThemedLightBackground, ThemedLightMark)
            AdaptiveIcon(mark, CircleShape, 84.dp, ThemedDarkBackground, ThemedDarkMark)
            AdaptiveIcon(mark, CircleShape, 56.dp)
            AdaptiveIcon(mark, SquircleShape, 48.dp)
            AdaptiveIcon(mark, CircleShape, 40.dp)
        }
    }
}

/**
 * One adaptive icon as a launcher draws it: the 108 dp layers scaled so the
 * mask shows their central 72 dp. [tint] recolours the mark like a themed icon.
 */
@Composable
private fun AdaptiveIcon(
    @DrawableRes mark: Int,
    mask: Shape,
    size: Dp,
    background: Color = CinexTheme.colors.background,
    tint: Color? = null,
) {
    Box(
        Modifier
            .size(size)
            .clip(mask)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(mark),
            contentDescription = null,
            colorFilter = tint?.let { ColorFilter.tint(it) },
            modifier = Modifier.requiredSize(size * 108f / 72f),
        )
    }
}

/** A superellipse (n = 4), close to the squircle mask of many launchers. */
private val SquircleShape: Shape = GenericShape { size, _ ->
    val steps = 120
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val x = size.width / 2 * (1 + sign(cos(t)) * abs(cos(t)).pow(0.5))
        val y = size.height / 2 * (1 + sign(sin(t)) * abs(sin(t)).pow(0.5))
        if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
    }
    close()
}
