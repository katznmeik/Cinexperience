package io.github.katznmeik.cinexperience.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/*
 * Room tables (spec §4). Column names are snake_case and must never be renamed
 * without a migration. Enums are stored by their explicit `storageKey`, the seat
 * layout as versioned JSON (see LayoutJson.kt).
 *
 * Deleting a cinema deletes its halls; deleting a hall deletes its visits and
 * seat overrides (ON DELETE CASCADE). The UI confirms before deleting.
 */

@Entity(
    tableName = "cinema",
    indices = [Index(value = ["seed_key"], unique = true)],
)
internal data class CinemaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String?,
    val notes: String?,
    @ColumnInfo(name = "is_preloaded") val isPreloaded: Boolean,
    /** Stable key from the seed asset, null for user-added cinemas. */
    @ColumnInfo(name = "seed_key") val seedKey: String?,
)

@Entity(
    tableName = "hall",
    foreignKeys = [
        ForeignKey(
            entity = CinemaEntity::class,
            parentColumns = ["id"],
            childColumns = ["cinema_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["cinema_id"]),
        Index(value = ["seed_key"], unique = true),
    ],
)
internal data class HallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "cinema_id") val cinemaId: Long,
    val name: String,
    /** The SeatLayout as versioned JSON; null until drawn. */
    @ColumnInfo(name = "layout_json") val layoutJson: String?,
    @ColumnInfo(name = "screen_width") val screenWidth: Float?,
    @ColumnInfo(name = "screen_distance") val screenDistance: Float?,
    /** App-private relative file path. */
    @ColumnInfo(name = "reference_image_path") val referenceImagePath: String?,
    val notes: String?,
    @ColumnInfo(name = "is_preloaded") val isPreloaded: Boolean,
    /** Stable key from the seed asset (globally unique, e.g. "delphi-lux/saal-3"). */
    @ColumnInfo(name = "seed_key") val seedKey: String?,
)

@Entity(
    tableName = "visit",
    foreignKeys = [
        ForeignKey(
            entity = HallEntity::class,
            parentColumns = ["id"],
            childColumns = ["hall_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["hall_id"]), Index(value = ["date"])],
)
internal data class VisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "hall_id") val hallId: Long,
    /** ISO-8601 date (yyyy-MM-dd), so it sorts correctly as text. */
    val date: LocalDate,
    @ColumnInfo(name = "film_title") val filmTitle: String?,
    /** SeatId within the hall's layout; not a foreign key (seats live in the layout JSON). */
    @ColumnInfo(name = "seat_id") val seatId: Int?,
    /** ScreeningFormat.storageKey */
    @ColumnInfo(name = "screening_format") val screeningFormat: String?,
    // Hall ratings (1–5, null = unrated).
    @ColumnInfo(name = "rating_sound") val ratingSound: Int?,
    @ColumnInfo(name = "rating_picture") val ratingPicture: Int?,
    @ColumnInfo(name = "rating_screen_size") val ratingScreenSize: Int?,
    @ColumnInfo(name = "rating_seat_comfort") val ratingSeatComfort: Int?,
    @ColumnInfo(name = "rating_legroom") val ratingLegroom: Int?,
    @ColumnInfo(name = "rating_steepness") val ratingSteepness: Int?,
    @ColumnInfo(name = "seat_rating") val seatRating: Int?,
    // Cinema ratings (spec §4.5), stored per visit.
    @ColumnInfo(name = "cinema_rating_atmosphere") val cinemaRatingAtmosphere: Int?,
    @ColumnInfo(name = "cinema_rating_bar_snacks") val cinemaRatingBarSnacks: Int?,
    @ColumnInfo(name = "cinema_rating_popcorn") val cinemaRatingPopcorn: Int?,
    @ColumnInfo(name = "cinema_rating_seat_comfort") val cinemaRatingSeatComfort: Int?,
    val note: String?,
    /** App-private relative file path. */
    @ColumnInfo(name = "photo_path") val photoPath: String?,
)

@Entity(
    tableName = "seat_override",
    primaryKeys = ["hall_id", "seat_id"],
    foreignKeys = [
        ForeignKey(
            entity = HallEntity::class,
            parentColumns = ["id"],
            childColumns = ["hall_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SeatOverrideEntity(
    @ColumnInfo(name = "hall_id") val hallId: Long,
    @ColumnInfo(name = "seat_id") val seatId: Int,
    /** SeatColour.storageKey, null = automatic colour. */
    val colour: String?,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean,
)

/** Small key/value store for database bookkeeping (e.g. the applied seed version). */
@Entity(tableName = "app_metadata")
internal data class MetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)
