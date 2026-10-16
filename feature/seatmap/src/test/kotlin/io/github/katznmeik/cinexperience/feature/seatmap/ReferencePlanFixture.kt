package io.github.katznmeik.cinexperience.feature.seatmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** A generated stand-in for a Yorck-app seat plan screenshot (never a real one), for the editor's reference panel. */
internal object ReferencePlanFixture {

    /** Light background, a screen bar and ten rows of grey seats with two aisles; row 4 is staggered. */
    fun hallPlan(): Bitmap {
        val bitmap = Bitmap.createBitmap(540, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF2F2F2.toInt())
        val seat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E8E.toInt() }
        val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt() }
        canvas.drawRoundRect(RectF(110f, 30f, 430f, 44f), 7f, 7f, screen)
        val pitch = 22f
        for (row in 0 until 10) {
            val shift = if (row == 3) pitch / 2 else 0f
            for (column in 0 until 22) {
                if (column == 4 || column == 17) continue // aisles
                val x = 28f + column * pitch + shift
                val y = 100f + row * 28f
                canvas.drawRoundRect(RectF(x, y, x + 17f, y + 17f), 4f, 4f, seat)
            }
        }
        return bitmap
    }
}
