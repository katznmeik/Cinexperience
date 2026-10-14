package io.github.katznmeik.cinexperience.feature.visits

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import java.time.LocalDate

/** Invented visits with films, seats and formats for the visit screenshots (never used by the app). */
internal object VisitFixtures {
    const val EDITED_VISIT = 9_001L

    private val row7 = SampleLayouts.splitHall.rows[6].seatIds
    private val row5 = SampleLayouts.splitHall.rows[4].seatIds

    val visits: List<Visit> = listOf(
        Visit(
            id = EDITED_VISIT,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 10, 3),
            filmTitle = "Perfect Days",
            seatId = row7[13], // row 7, seat 10
            screeningFormat = ScreeningFormat.OMU,
            seatRating = Rating(4),
            hallRatings = HallRatings(sound = Rating(5), picture = Rating(4), screenSize = Rating(4), legroom = Rating(3)),
            cinemaRatings = CinemaRatings(atmosphere = Rating(5), popcorn = Rating(3)),
            note = "Row 7 is perfect: centred, eye level with the screen.",
            photoPath = "photos/visit-fixture.jpg",
        ),
        Visit(
            id = 9_002,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 9, 19),
            filmTitle = "The Brutalist",
            seatId = row5[10],
            screeningFormat = ScreeningFormat.SEVENTY_MM,
            seatRating = Rating(5),
        ),
        Visit(
            id = 9_003,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 8, 30),
            seatId = row5[2],
            seatRating = Rating(2),
        ),
        Visit(
            id = 9_004,
            hallId = SampleData.BABYLON * 100 + 1,
            date = LocalDate.of(2026, 9, 27),
            filmTitle = "Anora",
            screeningFormat = ScreeningFormat.OV,
        ),
        Visit(
            id = 9_005,
            hallId = 801,
            date = LocalDate.of(2026, 9, 5),
            filmTitle = "Das Lehrerzimmer",
            screeningFormat = ScreeningFormat.GERMAN_DUB,
        ),
    )

    /** The split-hall store with only [visits] (no anonymous sample visits). */
    fun store(): FakeStore = SampleData.storeWithSplitHall().copy(visits = visits)

    /** A generated stand-in for a photo of a cinema screen: dark room, bright screen, seat rows. */
    fun photo(): Bitmap {
        val bitmap = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF15110E.toInt())
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 40f, 0f, 200f, 0xFFE8E2D6.toInt(), 0xFF6E655A.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(60f, 40f, 420f, 190f), 6f, 6f, glow)
        val seat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A2A22.toInt() }
        for (row in 0 until 4) {
            val y = 230f + row * 32f
            for (column in 0 until 12) {
                val x = 20f + column * 38f + (row % 2) * 10f
                canvas.drawRoundRect(RectF(x, y, x + 30f, y + 26f), 6f, 6f, seat)
            }
        }
        return bitmap
    }
}
