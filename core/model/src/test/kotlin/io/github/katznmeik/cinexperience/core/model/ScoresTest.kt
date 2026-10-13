package io.github.katznmeik.cinexperience.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoresTest {

    private val date = LocalDate.of(2026, 10, 1)
    private fun r(value: Int) = Rating(value)

    private fun visit(
        hallId: Long = 1,
        hall: HallRatings = HallRatings.NONE,
        cinema: CinemaRatings = CinemaRatings.NONE,
    ) = Visit(hallId = hallId, date = date, hallRatings = hall, cinemaRatings = cinema)

    @Test
    fun categoryScoreIgnoresNullAndHasNoScoreWithoutRatings() {
        assertNull(categoryScore(emptyList()))
        assertNull(categoryScore(listOf(null, null)))
        assertEquals(CategoryScore(4.0, 2), categoryScore(listOf(r(3), null, r(5))))
    }

    @Test
    fun hallScoresWithoutVisitsOrRatingsAreAbsent() {
        assertEquals(HallScores(emptyMap(), null, 0), hallScores(emptyList()))
        val unrated = hallScores(listOf(visit(), visit()))
        assertTrue(unrated.categories.isEmpty())
        assertNull(unrated.overall)
        assertEquals(2, unrated.visitCount)
    }

    @Test
    fun hallCategoryScoresCountOnlyNonEmptyValues() {
        val scores = hallScores(
            listOf(
                visit(hall = HallRatings(sound = r(5), picture = r(4))),
                visit(hall = HallRatings(sound = r(3))),
                visit(),
            ),
        )
        assertEquals(CategoryScore(4.0, 2), scores[HallCategory.SOUND])
        assertEquals(CategoryScore(4.0, 1), scores[HallCategory.PICTURE])
        assertNull(scores[HallCategory.LEGROOM])
        assertEquals(3, scores.visitCount)
    }

    @Test
    fun hallOverallIsMeanOfCategoriesWithDataNotOfVisits() {
        // Sound has 3 ratings averaging 2.0, picture 1 rating of 5. Overall is the
        // unweighted mean of the two category averages: 3.5.
        val scores = hallScores(
            listOf(
                visit(hall = HallRatings(sound = r(2), picture = r(5))),
                visit(hall = HallRatings(sound = r(2))),
                visit(hall = HallRatings(sound = r(2))),
            ),
        )
        assertEquals(3.5, scores.overall)
    }

    @Test
    fun allSixCategoriesAreAggregated() {
        val all = HallRatings(r(1), r(2), r(3), r(4), r(5), r(3))
        val scores = hallScores(listOf(visit(hall = all)))
        assertEquals(6, scores.categories.size)
        HallCategory.entries.forEach { assertEquals(all[it]!!.value.toDouble(), scores[it]!!.average) }
        assertEquals(18.0 / 6, scores.overall)
    }

    @Test
    fun cinemaScoresCombineCinemaCategoriesAndHallOverallScores() {
        val scores = cinemaScores(
            mapOf(
                1L to listOf(
                    visit(1, HallRatings(sound = r(4)), CinemaRatings(atmosphere = r(5), popcorn = r(2))),
                ),
                2L to listOf(
                    visit(2, HallRatings(sound = r(2)), CinemaRatings(atmosphere = r(3))),
                    visit(2, cinema = CinemaRatings(barSnacks = r(4))),
                ),
                3L to emptyList(),
            ),
        )
        assertEquals(CategoryScore(4.0, 2), scores[CinemaCategory.ATMOSPHERE])
        assertEquals(CategoryScore(4.0, 1), scores[CinemaCategory.BAR_SNACKS])
        assertEquals(CategoryScore(2.0, 1), scores[CinemaCategory.POPCORN])
        assertNull(scores[CinemaCategory.SEAT_COMFORT])
        assertEquals(4.0, scores.halls.getValue(1).overall)
        assertEquals(2.0, scores.halls.getValue(2).overall)
        assertNull(scores.halls.getValue(3).overall)
        // Mean of 4.0, 4.0, 2.0 (cinema categories) and 4.0, 2.0 (halls); hall 3 has no data.
        assertEquals(16.0 / 5, scores.overall)
        assertEquals(3, scores.visitCount)
    }

    @Test
    fun cinemaWithOnlyHallRatingsOrOnlyCinemaRatings() {
        val onlyHall = cinemaScores(mapOf(1L to listOf(visit(hall = HallRatings(picture = r(3))))))
        assertEquals(3.0, onlyHall.overall)
        assertTrue(onlyHall.categories.isEmpty())

        val onlyCinema = cinemaScores(mapOf(1L to listOf(visit(cinema = CinemaRatings(seatComfort = r(1))))))
        assertEquals(1.0, onlyCinema.overall)
        assertNull(onlyCinema.halls.getValue(1).overall)
    }

    @Test
    fun cinemaWithoutDataHasNoOverall() {
        assertNull(cinemaScores(emptyMap()).overall)
        assertNull(cinemaScores(mapOf(1L to emptyList(), 2L to listOf(visit()))).overall)
    }

    @Test
    fun ratingsAccessorsCoverEveryCategory() {
        var hall = HallRatings.NONE
        HallCategory.entries.forEachIndexed { i, category -> hall = hall.withRating(category, r(i % 5 + 1)) }
        HallCategory.entries.forEachIndexed { i, category -> assertEquals(r(i % 5 + 1), hall[category]) }
        assertNull(hall.withRating(HallCategory.LEGROOM, null).legroom)

        var cinema = CinemaRatings.NONE
        CinemaCategory.entries.forEachIndexed { i, category -> cinema = cinema.withRating(category, r(i + 1)) }
        CinemaCategory.entries.forEachIndexed { i, category -> assertEquals(r(i + 1), cinema[category]) }
        assertNull(cinema.withRating(CinemaCategory.POPCORN, null).popcorn)
    }

    @Test
    fun formatScoreUsesOneDecimalHalfUpAndDashForUnrated() {
        assertEquals("–", formatScore(null))
        assertEquals("4.0", formatScore(4.0))
        assertEquals("4.3", formatScore(4.25))
        assertEquals("4.4", formatScore(4.35))
        assertEquals("3.3", formatScore(10.0 / 3))
        assertEquals("5.0", formatScore(5.0))
    }

    @Test
    fun formatScoreIsNotFooledByFloatingPointNoise() {
        // Mean of category means 3.6, 3.75 and 4.5 is exactly 3.95.
        val mean = listOf(3.6, 3.75, 4.5).average()
        assertTrue(mean < 3.95) // the double really is just below
        assertEquals("4.0", formatScore(mean))
        assertEquals("2.5", formatScore(2.45))
        assertEquals("3.9", formatScore(3.94))
        // The same through the real aggregation: hall overall of those category means.
        val visits = listOf(
            visit(hall = HallRatings(sound = r(3), picture = r(4), screenSize = r(4))),
            visit(hall = HallRatings(sound = r(4), picture = r(4), screenSize = r(5))),
            visit(hall = HallRatings(sound = r(4), picture = r(3))),
            visit(hall = HallRatings(sound = r(4), picture = r(4))),
            visit(hall = HallRatings(sound = r(3))),
        )
        val scores = hallScores(visits)
        assertEquals(3.6, scores[HallCategory.SOUND]!!.average, 1e-12)
        assertEquals(3.75, scores[HallCategory.PICTURE]!!.average, 1e-12)
        assertEquals(4.5, scores[HallCategory.SCREEN_SIZE]!!.average, 1e-12)
        assertEquals("4.0", formatScore(scores.overall))
    }
}
