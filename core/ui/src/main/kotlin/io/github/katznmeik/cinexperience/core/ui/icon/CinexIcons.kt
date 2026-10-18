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

    /** The Program tab (v1.1): a cinema ticket with a perforation. */
    val Program: ImageVector by lazy {
        strokeIcon(
            "Program",
            "M4 6h16a1 1 0 0 1 1 1v3a2 2 0 0 0 0 4v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a2 2 0 0 0 0-4V7a1 1 0 0 1 1-1z",
            "M15 6v2M15 11v2M15 16v2",
        )
    }

    val Refresh: ImageVector by lazy { strokeIcon("Refresh", "M20 12a8 8 0 1 1-2.4-5.7", "M20 4v4.5h-4.5") }

    /** Opens a page in the browser (e.g. "Book on yorck.de"). */
    val External: ImageVector by lazy { strokeIcon("External", "M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5") }

    val Search: ImageVector by lazy { strokeIcon("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4") }

    val Filter: ImageVector by lazy { strokeIcon("Filter", "M4 6h16M7 12h10M10 18h4") }

    /** Saved filters. */
    val Bookmark: ImageVector by lazy { strokeIcon("Bookmark", "M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z") }

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

    /** Four squares: "Edit layout". */
    val Grid: ImageVector by lazy {
        strokeIcon("Grid", "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z")
    }

    /** Picture frame with a mountain: reference screenshot. */
    val Image: ImageVector by lazy {
        strokeIcon(
            "Image",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M7 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
            "M21 15l-5-5L5 21",
        )
    }

    /** "×": close a form. */
    val Close: ImageVector by lazy { strokeIcon("Close", "M6 6l12 12M18 6L6 18") }

    /** "✓": marks the active choice of a button row (e.g. a seat's colour override). */
    val Check: ImageVector by lazy { strokeIcon("Check", "M5 12.5l4.5 4.5L19 7.5", strokeWidth = 2.6f) }

    /** Small "▾" of dropdown-like fields. */
    val ChevronDown: ImageVector by lazy { strokeIcon("ChevronDown", "M6 9l6 6 6-6") }

    val Camera: ImageVector by lazy {
        strokeIcon(
            "Camera",
            "M4 7h3l2-3h6l2 3h3a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z",
            "M8.5 13a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0",
        )
    }

    /** Outline of [Star]: an empty rating star. */
    val StarOutline: ImageVector by lazy {
        strokeIcon("StarOutline", "M12 2l3 6.9 7.5.7-5.7 5 1.7 7.4L12 18l-6.5 4 1.7-7.4-5.7-5 7.5-.7z", strokeWidth = 1.6f)
    }

    val Undo: ImageVector by lazy { strokeIcon("Undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11") }

    val Redo: ImageVector by lazy { strokeIcon("Redo", "M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13") }

    /** Four corners: fit the view to the content. */
    val Fit: ImageVector by lazy {
        strokeIcon(
            "Fit",
            "M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3",
        )
    }

    val Delete: ImageVector by lazy { strokeIcon("Delete", "M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6") }

    /** A ticket, e.g. the mark of a seat of the booking: the same glyph as [Program]. */
    val Ticket: ImageVector get() = Program

    /** A page with an arrow into it: importing a file, e.g. a ticket PDF (Settings → Import ticket). */
    val ImportFile: ImageVector by lazy { strokeIcon("ImportFile", "M6 3h8l4 4v14H6z", "M14 3v4h4", "M12 10v7M9 14l3 3l3-3") }

    /** Seat kind glyphs (see [SeatKindGlyphs]), as icons for the editor's seat type chips. */
    val Wheelchair: ImageVector by lazy { strokeIcon("Wheelchair", *SeatKindGlyphs.Wheelchair.toTypedArray()) }

    val CompanionSeat: ImageVector by lazy { strokeIcon("CompanionSeat", *SeatKindGlyphs.CompanionSeat.toTypedArray()) }

    val Sofa: ImageVector by lazy { strokeIcon("Sofa", *SeatKindGlyphs.Sofa.toTypedArray()) }

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
