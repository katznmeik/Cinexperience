package io.github.katznmeik.cinexperience.feature.tickets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Light modules around the code on every side (the QR standard's quiet zone). */
internal const val QUIET_ZONE_MODULES = 4

/** Read by screen readers for the QR code. */
internal const val QR_CODE_DESCRIPTION = "Ticket QR code"

/**
 * Where the modules go on a square panel of [sidePx] pixels for a code of [size]
 * modules per side: every module is [modulePx] whole pixels (so its edges are
 * sharp, never blended), as large as fits with the [QUIET_ZONE_MODULES] quiet
 * zone on each side; the code is centred, starting [offsetPx] from the panel's
 * top left. Leftover pixels widen the quiet zone.
 */
internal data class QrGeometry(val modulePx: Int, val offsetPx: Int) {
    companion object {
        fun of(sidePx: Int, size: Int): QrGeometry {
            val modulePx = (sidePx / (size + 2 * QUIET_ZONE_MODULES)).coerceAtLeast(1)
            return QrGeometry(modulePx, ((sidePx - modulePx * size) / 2).coerceAtLeast(0))
        }
    }
}

/**
 * The ticket's QR code (spec §6.11, §7): black modules on a white panel with the
 * quiet zone, drawn from [modules] (the app never decodes or generates QR codes),
 * as large as the width allows. The panel is the one light surface in the app.
 */
@Composable
internal fun QrCodePanel(modules: QrModules, modifier: Modifier = Modifier) {
    val dark = CinexTheme.colors.ticketQrModule
    val light = CinexTheme.colors.ticketQrBackground
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .size(maxWidth)
                .clip(RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = QR_CODE_DESCRIPTION
                    role = Role.Image
                },
        ) { drawQrCode(modules, dark, light) }
    }
}

/** Draws [modules] on a [light] panel as large as fits, with whole-pixel modules (see [QrGeometry]). */
internal fun DrawScope.drawQrCode(modules: QrModules, dark: Color, light: Color) {
    drawRect(light, size = size)
    val n = modules.size
    val (m, offset) = QrGeometry.of(minOf(size.width, size.height).toInt(), n)
    for (row in 0 until n) {
        // One rectangle per run of dark modules: no seams between neighbours.
        var column = 0
        while (column < n) {
            if (!modules.isDark(row, column)) {
                column++
                continue
            }
            val start = column
            while (column < n && modules.isDark(row, column)) column++
            drawRect(
                color = dark,
                topLeft = Offset((offset + start * m).toFloat(), (offset + row * m).toFloat()),
                size = Size(((column - start) * m).toFloat(), m.toFloat()),
            )
        }
    }
}
