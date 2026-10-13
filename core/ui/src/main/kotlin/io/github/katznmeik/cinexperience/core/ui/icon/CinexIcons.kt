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

    val Edit: ImageVector by lazy { strokeIcon("Edit", "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z") }

    /** Vertical three-dot overflow menu. */
    val More: ImageVector by lazy {
        strokeIcon("More", "M12 5h.01M12 12h.01M12 19h.01", strokeWidth = 3f)
    }

    val Export: ImageVector by lazy { strokeIcon("Export", "M12 3v12M7 10l5 5 5-5M4 19h16") }

    val Import: ImageVector by lazy { strokeIcon("Import", "M12 15V3M7 8l5-5 5 5M4 19h16") }

    val Cloud: ImageVector by lazy { strokeIcon("Cloud", "M7 18a5 5 0 0 1-.5-10A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9z") }

    val List: ImageVector by lazy {
        strokeIcon("List", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01")
    }

    /** Filled star marking the favourite seat (spec §5). */
    val Star: ImageVector by lazy {
        ImageVector.Builder(
            name = "Star",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString("M12 2l3 6.9 7.5.7-5.7 5 1.7 7.4L12 18l-6.5 4 1.7-7.4-5.7-5 7.5-.7z").toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

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
