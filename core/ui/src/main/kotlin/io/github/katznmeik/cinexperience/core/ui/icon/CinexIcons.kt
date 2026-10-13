package io.github.katznmeik.cinexperience.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons from the mockups (24×24 viewport, 2 px round stroke). They are tinted
 * by `Icon(tint = …)`, so the stroke colour here is irrelevant.
 */
object CinexIcons {
    val Cinemas: ImageVector by lazy {
        strokeIcon(
            "Cinemas",
            "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
            "M3 10h18M8 5v5M16 5v5",
        )
    }

    val Rankings: ImageVector by lazy {
        strokeIcon("Rankings", "M6 20V10M12 20V4M18 20v-7")
    }

    val Settings: ImageVector by lazy {
        strokeIcon(
            "Settings",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0z",
            "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1",
        )
    }

    val Back: ImageVector by lazy { strokeIcon("Back", "M15 18l-6-6 6-6") }

    val ChevronRight: ImageVector by lazy { strokeIcon("ChevronRight", "M9 6l6 6-6 6") }

    val Add: ImageVector by lazy { strokeIcon("Add", "M12 5v14M5 12h14", strokeWidth = 2.2f) }

    private fun strokeIcon(name: String, vararg paths: String, strokeWidth: Float = 2f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            for (path in paths) {
                addPath(
                    pathData = PathParser().parsePathString(path).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
