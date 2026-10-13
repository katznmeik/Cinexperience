package io.github.katznmeik.cinexperience.core.model

/** The six per-hall rating categories (spec §4.4). */
enum class HallCategory(val displayName: String) {
    SOUND("Sound"),
    PICTURE("Picture"),
    SCREEN_SIZE("Screen size"),
    SEAT_COMFORT("Seat comfort"),
    LEGROOM("Legroom"),
    STEEPNESS("Seating steepness"),
}

/** The four cinema-level rating categories (spec §4.5). */
enum class CinemaCategory(val displayName: String) {
    ATMOSPHERE("Atmosphere"),
    BAR_SNACKS("Bar / snacks"),
    POPCORN("Popcorn quality"),
    SEAT_COMFORT("Seat comfort"),
}

/** A visit's six optional hall ratings. */
data class HallRatings(
    val sound: Rating? = null,
    val picture: Rating? = null,
    val screenSize: Rating? = null,
    val seatComfort: Rating? = null,
    val legroom: Rating? = null,
    val steepness: Rating? = null,
) {
    operator fun get(category: HallCategory): Rating? = when (category) {
        HallCategory.SOUND -> sound
        HallCategory.PICTURE -> picture
        HallCategory.SCREEN_SIZE -> screenSize
        HallCategory.SEAT_COMFORT -> seatComfort
        HallCategory.LEGROOM -> legroom
        HallCategory.STEEPNESS -> steepness
    }

    /** Returns a copy with [category] set to [rating] (`null` clears it). */
    fun withRating(category: HallCategory, rating: Rating?): HallRatings = when (category) {
        HallCategory.SOUND -> copy(sound = rating)
        HallCategory.PICTURE -> copy(picture = rating)
        HallCategory.SCREEN_SIZE -> copy(screenSize = rating)
        HallCategory.SEAT_COMFORT -> copy(seatComfort = rating)
        HallCategory.LEGROOM -> copy(legroom = rating)
        HallCategory.STEEPNESS -> copy(steepness = rating)
    }

    companion object {
        val NONE: HallRatings = HallRatings()
    }
}

/** A visit's four optional cinema ratings (spec §4.5). */
data class CinemaRatings(
    val atmosphere: Rating? = null,
    val barSnacks: Rating? = null,
    val popcorn: Rating? = null,
    val seatComfort: Rating? = null,
) {
    operator fun get(category: CinemaCategory): Rating? = when (category) {
        CinemaCategory.ATMOSPHERE -> atmosphere
        CinemaCategory.BAR_SNACKS -> barSnacks
        CinemaCategory.POPCORN -> popcorn
        CinemaCategory.SEAT_COMFORT -> seatComfort
    }

    fun withRating(category: CinemaCategory, rating: Rating?): CinemaRatings = when (category) {
        CinemaCategory.ATMOSPHERE -> copy(atmosphere = rating)
        CinemaCategory.BAR_SNACKS -> copy(barSnacks = rating)
        CinemaCategory.POPCORN -> copy(popcorn = rating)
        CinemaCategory.SEAT_COMFORT -> copy(seatComfort = rating)
    }

    companion object {
        val NONE: CinemaRatings = CinemaRatings()
    }
}
